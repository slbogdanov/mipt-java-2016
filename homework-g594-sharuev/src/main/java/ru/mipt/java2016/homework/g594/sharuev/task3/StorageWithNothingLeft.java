package ru.mipt.java2016.homework.g594.sharuev.task3;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.primitives.Longs;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;

/**
 * Организация этой штуковины:
 * cache хранит последние обработанные пары. Поиск сначала осуществляется по нему.
 * Он поддерживается актуальным в процессе всех операций с хранилищем.
 * Последние записи при превышении некоторого порога удаляются.
 * В памяти хранится MemTable. Изменение и запись осуществляются в неё.
 * Поиск сначала по ней. Если не нашли, идём в последний из indexMaps, и так далее до первого.
 * Первый и есть вся база данных. Если все эти файлы слить с первым,
 * то получится нужная копия для персистентного хранения (ещё ключи добавить).
 *
 * @param <K>
 * @param <V>
 */
class StorageWithNothingLeft<K, V> implements
        ru.mipt.java2016.homework.base.task2.KeyValueStorage {

    class Part {

        protected RandomAccessFile raf;
        protected File file;
        protected ArrayList<K> keys;

        Part(RandomAccessFile rafVal, File fileVal) throws IOException {
            raf = rafVal;
            file = fileVal;
            keys = new ArrayList<>();
        }

        private V read(long offset) {
            try {
                /*if (offset - curPos >= 0 && offset - curPos < Consts.SMALL_BUFFER_SIZE - Consts.MAX_VALUE_SIZE - 2) {
                    dis.reset();
                    dis.skip(offset - curPos);
                } else {
                    raf.seek(offset);
                    curPos = raf.getFilePointer();
                    dis = bdisFromRaf(raf, Consts.SMALL_BUFFER_SIZE);
                    dis.mark(Consts.SMALL_BUFFER_SIZE);
                }*/
                raf.seek(offset);
                DataInputStream dis = bdisFromRaf(raf, Consts.MAX_VALUE_SIZE);
                return valueSerializationStrategy.deserializeFromStream(dis);
            } catch (Exception e) {
                throw new KVSException("Failed to read from disk", e);
            }
        }
    }

    class Address {
        Address(Part part, long offset) {
            this.part = part;
            this.offset = offset;
        }

        protected long offset;
        protected Part part;
    }

    private Map<K, V> memTable;
    private LoadingCache<K, V> cache;
    protected Map<K, Address> indexTable;
    protected SerializationStrategy<K> keySerializationStrategy;
    protected SerializationStrategy<V> valueSerializationStrategy;
    private boolean isOpen;
    protected final String dbName;
    protected static String path;
    protected Deque<Part> parts;
    private File lockFile;
    private int nextFileIndex = 0;
    private Validator validator;
    private File keyStorageFile;
    private File valueStorageFile;

    StorageWithNothingLeft(String path, SerializationStrategy<K> keySerializationStrategy,
                           SerializationStrategy<V> valueSerializationStrategy,
                           Comparator<K> comparator) throws KVSException {
        memTable = new HashMap<>();
        this.keySerializationStrategy = keySerializationStrategy;
        this.valueSerializationStrategy = valueSerializationStrategy;
        indexTable = new HashMap<K, Address>();
        dbName = keySerializationStrategy.getSerializingClass().getSimpleName() +
                valueSerializationStrategy.getSerializingClass().getSimpleName();
        parts = new ArrayDeque<>();
        this.path = path;
        validator = new Validator();
        cache = CacheBuilder.newBuilder()
                .maximumSize(Consts.CACHE_SIZE)
                .build(
                        new CacheLoader<K, V>() {
                            public V load(K key) { // no checked exception
                                V val = memTable.get(key);
                                if (val != null) {
                                    return val;
                                }
                                Address address = indexTable.get(key);
                                val = address.part.read(address.offset);
                                if (val != null) {
                                    return val;
                                } else {
                                    throw new NotFoundException();
                                }
                            }
                        });

        // Создать lock-файл
        lockFile = Paths.get(path, dbName + Consts.STORAGE_LOCK_SUFF).toFile();
        try {
            if (!lockFile.createNewFile()) {
                throw new KVSException("Storage was already opened");
            }
        } catch (IOException e) {
            throw new KVSException("Failed to lockFile database");
        }

        // Проверить хэш/создать новый файл
        boolean isNew = false;
        keyStorageFile = Paths.get(path, dbName + Consts.KEY_STORAGE_NAME_SUFF).toFile();
        valueStorageFile = Paths.get(path, dbName + Consts.VALUE_STORAGE_NAME_SUFF).toFile();
        try {
            boolean wasCreated = keyStorageFile.createNewFile();
            if (wasCreated) {
                isNew = true;
                if (!valueStorageFile.createNewFile()) {
                    throw new KVSException("Values file found but keys file is missing");
                }
            } else {
                if (!valueStorageFile.exists()) {
                    throw new KVSException("Keys file found but value file is missing");
                }
                validator.checkHash(path);
            }
        } catch (IOException e) {
            throw new KVSException("Failed to create file", e);
        }

        // Открыть файл
        try {
            //keyStorageRaf = new RandomAccessFile(keyStorageFile, "rw");
            parts.addLast(new Part(new RandomAccessFile(valueStorageFile, "rw"),
                    valueStorageFile));
        } catch (FileNotFoundException e) {
            throw new KVSException("File of database was deleted", e);
        } catch (IOException e) {
            throw new KVSException("IO error at db file", e);
        }

        // Подгрузить данные с диска
        try {
            if (!isNew) {
                initDatabaseFromDisk();
            }
        } catch (SerializationException e) {
            throw new KVSException("Failed to read database", e);
        }

        isOpen = true;
    }

    /**
     * Возвращает значение, соответствующее ключу.
     * Сложность O().
     *
     * @param key - ключ, который нужно найти
     * @return Значение или null, если ключ не найден.
     */
    public Object read(Object key) {
        checkOpen();
        // Можно убрать, если редко будут неплодотворные обращения
        if (!indexTable.containsKey(key)) {
            return null;
        }
        try {
            return cache.getUnchecked((K) key);
        } catch (NotFoundException e) {
            return null;
        }
    }

    /**
     * Поиск ключа.
     * Сложность O(NlogN).
     *
     * @param key - ключ, который нужно найти.
     * @return true, если найден, false, если нет.
     */
    public boolean exists(Object key) {
        checkOpen();
        return indexTable.containsKey(key);
    }

    /**
     * Вставка пары ключ-значение.
     * Сложность O(log(N))
     *
     * @param key
     * @param value
     */
    public void write(Object key, Object value) {
        checkOpen();
        memTable.put((K) key, (V) value);
        indexTable.put((K) key, null);
        if (memTable.size() > Consts.DUMP_THRESHOLD) {
            dumpMemTableToFile();
            if (parts.size() > Consts.MERGE_THRESHOLD) {
                try {
                    while (parts.size() > 1) {
                        mergeFiles();
                    }
                } catch (IOException e) {
                    throw new KVSException("Lol");
                }
            }
        }
    }

    /**
     * Удаление ключа key.
     * Сложность: O(NlogN).
     */
    public void delete(Object key) {
        checkOpen();
        indexTable.remove(key);

    }

    /**
     * Сложность: как у итератора по ключам TreeMap.
     *
     * @return итератор по ключам.
     */
    public Iterator readKeys() {
        checkOpen();
        return indexTable.keySet().iterator();
    }

    /**
     * Сложность O(1).
     *
     * @return количество хранимых пар
     */
    public int size() {
        checkOpen();
        return indexTable.size();
    }

    /**
     * Закрытие хранилища.
     *
     * @throws IOException
     */
    public void close() throws IOException {
        checkOpen();
        dumpDatabaseToFile();
        validator.writeHash();
        if (!lockFile.delete()) {
            throw new IOException("Can't delete lock file");
        }
        //keyStorageRaf.close();
        isOpen = false;
    }

    /**
     * Считывание файла ключей в indexMap. Буферизуется.
     *
     * @throws SerializationException
     */
    private void initDatabaseFromDisk() throws SerializationException {
        try {
            DataInputStream dataInputStream = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(keyStorageFile),
                            Consts.BUFFER_SIZE));
            long numberOfEntries = dataInputStream.readLong();

            // Считываем ключи и оффсеты соответствующих значений
            for (long i = 0; i < numberOfEntries; ++i) {
                K key = keySerializationStrategy.deserializeFromStream(dataInputStream);
                long offset = dataInputStream.readLong();
                indexTable.put(key, new Address(parts.getLast(), offset));
                parts.getLast().keys.add(key);
            }
        } catch (IOException e) {
            throw new SerializationException("Read failed", e);
        }
    }

    /**
     * Складывает текущую MemTable в следующий по счёту part.
     * Буферизуется.
     */
    private void dumpMemTableToFile() {
        try {
            File nextFile = Paths.get(path,
                    dbName + nextFileIndex + Consts.STORAGE_PART_SUFF).toFile();
            ++nextFileIndex;
            Part nextPart = new Part(
                    new RandomAccessFile(nextFile, "rw"),
                    nextFile);
            DataOutputStream dataOutputStream = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(nextPart.file),
                            Consts.BUFFER_SIZE));

            for (Map.Entry<K, V> entry : memTable.entrySet()) {
                try {
                    indexTable.put(entry.getKey(), new Address(nextPart, dataOutputStream.size()));
                    nextPart.keys.add(entry.getKey());
                    valueSerializationStrategy.serializeToStream(entry.getValue(),
                            dataOutputStream);
                } catch (SerializationException e) {
                    throw new IOException("Serialization error");
                }
            }
            parts.addLast(nextPart);
            memTable.clear();
            dataOutputStream.flush();
        } catch (IOException e) {
            throw new KVSException("Failed to dump memtable to file", e);
        }
    }

    /**
     * Пишет всю базу на диск, считает хэши и удаляет lock-файлы.
     *
     * @throws IOException
     */
    private void dumpDatabaseToFile() throws IOException {

        // Записываем на диск последнюю MemTable
        dumpMemTableToFile();

        // Смержить всё один файл. После в единственном элементе indexMaps лежит
        // дерево из всех ключей с правильными оффсетами, а в partRAF - все соответствующие значения.
        while (parts.size() > 1) {
            mergeFiles();
        }

        // Пишем ключи и сдвиги.
        DataOutputStream keyDos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(keyStorageFile), Consts.BUFFER_SIZE));
        keyDos.writeLong(size());
        try {
            for (Map.Entry<K, Address> entry : indexTable.entrySet()) {
                keySerializationStrategy.serializeToStream(entry.getKey(), keyDos);
                keyDos.writeLong(entry.getValue().offset);
            }
        } catch (SerializationException e) {
            throw new IOException("Serialization error", e);
        }
        keyDos.flush();
    }

    /**
     * Смерживание двух частей в одну.
     * Берутся две части из начала дека, мержатся и итоговая часть кладётся в начало дека.
     * Мержатся они при помощи временного файла, который в конце переименовывается в имя первого из сливавшихся файлов.
     * Сложность O(Nlog(N))
     *
     * @throws IOException
     */
    protected void mergeFiles() throws IOException {
        File tempFile = Paths.get(path,
                dbName + "Temp" + Consts.STORAGE_PART_SUFF).toFile();
        if (!tempFile.createNewFile()) {
            throw new KVSException("Temp file already exists");
        }
        Part newPart = new Part(new RandomAccessFile(tempFile, "rw"),
                tempFile);
        DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(newPart.file),
                        Consts.BUFFER_SIZE));

        File bigFile = parts.getFirst().file;
        Map<K, Address> newIndexTable = new HashMap<>();

        try {
            while (parts.size() > 0) {
                Part curPart = parts.getLast();
                parts.pollLast();
                curPart.raf.seek(0);
                DataInputStream dis = new DataInputStream(
                        new BufferedInputStream(new FileInputStream(curPart.file),
                                Consts.BUFFER_SIZE));

                Iterator<K> keyIter = curPart.keys.iterator();
                while (keyIter.hasNext()) {
                    K key = keyIter.next();
                    if (indexTable.remove(key) != null) {
                        newIndexTable.put(key, new Address(newPart, out.size()));
                        newPart.keys.add(key);
                        valueSerializationStrategy.serializeToStream(
                                valueSerializationStrategy.deserializeFromStream(dis), out);
                    } else {
                        valueSerializationStrategy.deserializeFromStream(dis);
                    }
                }

                curPart.raf.close();
                if (!curPart.file.delete()) {
                    throw new KVSException(
                            String.format("Can't delete file %s", curPart.file.getName()));
                }
            }
        } catch (SerializationException e) {
            throw new IOException("Serialization error", e);
        }

        out.flush();
        newPart.raf.close();
        if (!newPart.file.renameTo(bigFile.getAbsoluteFile())) {
            throw new KVSException(
                    String.format("Can't rename temp file %s", newPart.file.getName()));
        }
        newPart.file = bigFile;
        newPart.raf = new RandomAccessFile(newPart.file, "rw");
        parts.addLast(newPart);
        indexTable = newIndexTable;
    }

    protected DataInputStream bdisFromRaf(RandomAccessFile raf, int bufferSize) {
        return new DataInputStream(new BufferedInputStream(
                Channels.newInputStream(raf.getChannel()), bufferSize));
    }

    private void checkOpen() {
        if (!isOpen) {
            throw new RuntimeException("Can't access closed storage");
        }
    }

    private class Validator {

        void countHash(File keyFile, Adler32 md) {
            try (InputStream is = new BufferedInputStream(new FileInputStream(keyFile),
                    Consts.BUFFER_SIZE);
                 CheckedInputStream dis = new CheckedInputStream(is, md)) {
                byte[] buf = new byte[Consts.BUFFER_SIZE];
                int response;
                do {
                    response = dis.read(buf);
                } while (response != -1);
            } catch (FileNotFoundException e) {
                throw new KVSException(
                        String.format("Can't find file %s", dbName + Consts.KEY_STORAGE_NAME_SUFF));
            } catch (IOException e) {
                throw new KVSException("Some IO error while reading hash");
            }
        }

        private byte[] countAllNeededHash() throws KVSException {
            // Создаём считатель хэша.
            Adler32 md;
            md = new Adler32();
            // Хэш файла ключей
            countHash(Paths.get(path, dbName + Consts.KEY_STORAGE_NAME_SUFF).toFile(), md);

            // Хэш файла значений
            countHash(Paths.get(path, dbName + Consts.VALUE_STORAGE_NAME_SUFF).toFile(), md);

            return Longs.toByteArray(md.getValue());
        }

        // Проверяет хэш сразу двух файлов.
        private void checkHash(String pathToFolder) throws KVSException {
            File hashFile = Paths.get(pathToFolder, dbName + Consts.STORAGE_HASH_SUFF).toFile();
            try {
                // Читаем файл хэша в буфер.
                ByteArrayOutputStream hashString = new ByteArrayOutputStream();
                try (InputStream ifs = new BufferedInputStream(new FileInputStream(hashFile))) {
                    int c;
                    while ((c = ifs.read()) != -1) {
                        hashString.write(c);
                    }
                }

                // Проверка.
                byte[] digest = countAllNeededHash();
                if (!Arrays.equals(digest, hashString.toByteArray())) {
                    throw new KVSException("Hash mismatch");
                }
            } catch (FileNotFoundException e) {
                throw new KVSException(
                        String.format("Can't find hash file %s",
                                dbName + Consts.STORAGE_HASH_SUFF));
            } catch (IOException e) {
                throw new KVSException("Some IO error while reading hash");
            }
        }

        private void writeHash() throws KVSException {
            try {
                File hashFile = Paths.get(path, dbName + Consts.STORAGE_HASH_SUFF).toFile();

                byte[] digest = countAllNeededHash();
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(hashFile))) {
                    os.write(digest);
                }

            } catch (FileNotFoundException e) {
                throw new KVSException(
                        String.format("Can't find hash file %s",
                                dbName + Consts.STORAGE_HASH_SUFF));
            } catch (IOException e) {
                throw new KVSException("Some IO error while reading hash");
            }
        }
    }

    static final class Consts {
        // Формат файла: V значение, ...
        static final String VALUE_STORAGE_NAME_SUFF = "ValueStorage.db";
        // Формат файла: long количество ключей, K ключ, long сдвиг, ...
        static final String KEY_STORAGE_NAME_SUFF = "KeyStorage.db";
        static final String STORAGE_HASH_SUFF = "StorageHash.db";
        static final String STORAGE_PART_SUFF = "Part.db";
        static final String STORAGE_LOCK_SUFF = "Lock.db";
        static final int CACHE_SIZE = 1;
        static final int DUMP_THRESHOLD = 1000;
        static final int MERGE_THRESHOLD = 102;
        //final static int KeySize = 100;
        static final int MAX_VALUE_SIZE = 1024 * 10;
        static final int SMALL_BUFFER_SIZE = MAX_VALUE_SIZE;
        static final int BUFFER_SIZE = MAX_VALUE_SIZE * 10;
    }
}
