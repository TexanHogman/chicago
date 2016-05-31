package com.xjeffrose.chicago;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.Env;
import org.rocksdb.HashLinkedListMemTableConfig;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;
import org.rocksdb.util.SizeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DBManager {
  private static final Logger log = LoggerFactory.getLogger(DBManager.class);

  private final Options options = new Options();
  private final ReadOptions readOptions = new ReadOptions();
  private final WriteOptions writeOptions = new WriteOptions();
  private final Map<String, ColumnFamilyHandle> columnFamilies = new HashMap<>();

  private RocksDB db;

  DBManager(ChiConfig config) {
    RocksDB.loadLibrary();

    configOptions();
    configReadOptions();
    configWriteOptions();

    try {
      File f = new File(config.getDBPath());
      if (f.exists()) {
        deleteDir(f);
      }
      this.db = RocksDB.open(options, config.getDBPath());
    } catch (RocksDBException e) {
      log.error("Could not load DB: " + config.getDBPath() + " " + e.getMessage());
      System.exit(-1);
    }
  }

  void deleteDir(File file) {
    File[] contents = file.listFiles();
    if (contents != null) {
      for (File f : contents) {
        deleteDir(f);
      }
    }
    file.delete();
  }

  private void configOptions() {
    Env env = Env.getDefault();
    env.setBackgroundThreads(20);

    options
        .createStatistics()
        .setCreateIfMissing(true)
        .setWriteBufferSize(8 * SizeUnit.KB)
        .setMaxWriteBufferNumber(3)
        .setMaxBackgroundCompactions(10)
        //.setCompressionType(CompressionType.SNAPPY_COMPRESSION)
        .setCompactionStyle(CompactionStyle.UNIVERSAL)
        .setEnv(env);

    options.setMemTableConfig(
        new HashLinkedListMemTableConfig()
            .setBucketCount(100000));


  }

  private void configReadOptions() {
    readOptions.setFillCache(false);
  }

  private void configWriteOptions() {
    writeOptions.setSync(true);
    writeOptions.setDisableWAL(true);
  }

  boolean colFamilyExists(byte[] name) {
    return columnFamilies.containsKey(new String(name));
  }

  boolean deleteColumnFamily(byte[] _name) {
    final String name = new String(_name);
    try {
      db.dropColumnFamily(columnFamilies.get(name));
      columnFamilies.remove(name);
      return true;
    } catch (RocksDBException e) {
      log.error("Could not delete Column Family: " + name, e);
      return false;
    }
  }

  private boolean createColumnFamily(byte[] name) {
    ColumnFamilyOptions columnFamilyOptions = new ColumnFamilyOptions();
    ColumnFamilyDescriptor columnFamilyDescriptor = new ColumnFamilyDescriptor(name, columnFamilyOptions);

    try {
      columnFamilies.put(new String(name), db.createColumnFamily(columnFamilyDescriptor));
      return true;
    } catch (RocksDBException e) {
      log.error("Could not create Column Family: " + new String(name), e);
      return false;
    }
  }

  boolean write(byte[] colFam, byte[] key, byte[] value) {
    if (key == null) {
      log.error("Tried to write a null key");
      return false;
    } else if (value == null) {
      log.error("Tried to write a null value");
      return false;
    } else if (!colFamilyExists(colFam)) {
      createColumnFamily(colFam);
    }
    try {
      db.put(columnFamilies.get(new String(colFam)), writeOptions, key, value);
      return true;
    } catch (RocksDBException e) {
      log.error("Error writing record: " + new String(key), e);
      return false;
    }
  }

  byte[] read(byte[] colFam, byte[] key) {
    if (key == null) {
      log.error("Tried to read a null key");
      return null;
    } else {
      try {
        byte[] res = db.get(columnFamilies.get(new String(colFam)), readOptions, key);
        return res;
      } catch (RocksDBException e) {
        log.error("Error getting record: " + new String(key), e);
        return null;
      }
    }
  }

  boolean delete(byte[] colFam, byte[] key) {
    if (key == null) {
      log.error("Tried to delete a null key");
      return false;
    } else {
      try {
        db.remove(columnFamilies.get(new String(colFam)), key);
        return true;
      } catch (RocksDBException e) {
        log.error("Error deleting record: " + new String(key), e);
        return false;
      }
    }
  }

  List<byte[]> getKeys(ReadOptions readOptions) {
    RocksIterator i = db.newIterator(readOptions);
    List<byte[]> keySet = new ArrayList();
    i.seekToFirst();

    while (i.isValid()) {
      keySet.add(i.key());
      i.next();
    }

    return keySet;
  }

  void destroy() {
    db.close();
  }

  public byte[] tsWrite(byte[] colFam, byte[] value) {
    if (value == null) {
      log.error("Tried to ts write a null value");
      return null;
    } else if (!colFamilyExists(colFam)) {
      createColumnFamily(colFam);
    }
    try {
      byte[] ts = Long.toString(System.nanoTime()).getBytes();
      db.put(columnFamilies.get(new String(colFam)), writeOptions, ts, value);
      return ts;
    } catch (RocksDBException e) {
      log.error("Error writing record: " + new String(colFam), e);
      return null;
    }
  }

  public byte[] stream(byte[] colFam) {
    byte[] offset = new byte[]{};
    return stream(colFam, offset);
  }

  public byte[] stream(byte[] colFam, byte[] offset) {
    if (colFamilyExists(colFam)) {
      RocksIterator i = db.newIterator(columnFamilies.get(new String(colFam)), readOptions);
      ByteBuf bb = Unpooled.buffer();

      if (offset.length == 0) {
        i.seekToFirst();
      } else {
        i.seek(offset);
      }

      while (i.isValid()) {
        byte[] v = i.value();
        byte[] _v = new byte[v.length + 1];
        System.arraycopy(v, 0, _v, 0, v.length);
        System.arraycopy(new byte[]{'\0'}, 0, _v, v.length, 1);
        bb.writeBytes(_v);
        i.next();
      }

      return bb.array();
    } else {
      return null;
    }
  }
}
