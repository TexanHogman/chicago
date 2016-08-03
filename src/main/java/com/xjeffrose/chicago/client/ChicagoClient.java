package com.xjeffrose.chicago.client;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.xjeffrose.chicago.ChiUtil;
import com.xjeffrose.chicago.DefaultChicagoMessage;
import com.xjeffrose.chicago.Op;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.util.internal.PlatformDependent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChicagoClient extends BaseChicagoClient {
  private static final Logger log = LoggerFactory.getLogger(ChicagoClient.class);

  private final Map<String, ByteBuf> streamBuffer = PlatformDependent.newConcurrentHashMap();

  public ChicagoClient(String zkConnectionString, int quorum) throws InterruptedException {
    super(zkConnectionString, quorum);
  }

  /*
   * Happy Path:
   * Delete -> send message to all (3) available nodes wait for all (3) responses to be true.
   * Write -> send message to all (3) available nodes wait for all (3) responses to be true.
   * Read -> send message to all (3) available nodes, wait for 1 node to reply, all other (2) replies are dropped.
   *
   * Fail Path:
   * Delete -> not all responses are true
   * Write -> not all responses are true
   * Read -> no nodes respond
   *
   * Reading from a node that hasn't been able to receive writes
   * Write fails, some nodes think that they have good data until they're told that they don't
   * interleaved writes from two different clients for the same key
   *
   *
   *
   *
   * two phase commit with multiple nodes
   *  write (key, value)
   *  ack x 3 nodes
   *  ok x 3 nodes -> write request
   */

  public ChicagoClient(String address) throws InterruptedException {
    super(address);
  }

  public ByteBuf aggregatedStream(byte[] offset) {
    ByteBuf responseStream = Unpooled.directBuffer();

    zkClient.getChildren(REPLICATION_LOCK_PATH).stream().parallel().forEach(xs -> {
      if (offset == null) {
        try {
          Futures.addCallback(stream(xs.getBytes()), new FutureCallback<List<byte[]>>() {
            @Override
            public void onSuccess(@Nullable List<byte[]> bytes) {
              responseStream.writeBytes(bytes.get(0));
            }

            @Override
            public void onFailure(Throwable throwable) {

            }
          });
        } catch (ChicagoClientTimeoutException e) {
          e.printStackTrace();
        }
      } else {
        try {
          Futures.addCallback(stream(xs.getBytes(), offset), new FutureCallback<List<byte[]>>() {
            @Override
            public void onSuccess(@Nullable List<byte[]> bytes) {
              responseStream.writeBytes(bytes.get(0));
            }

            @Override
            public void onFailure(Throwable throwable) {

            }
          });
        } catch (ChicagoClientTimeoutException e) {
          e.printStackTrace();
        }
      }
    });

    return responseStream;
  }

  public ListenableFuture<List<byte[]>> stream(byte[] key) throws ChicagoClientTimeoutException {
    return stream(key, null);
  }

  public ListenableFuture<List<byte[]>> stream(byte[] key, byte[] offset) throws ChicagoClientTimeoutException {
    List<ListenableFuture<byte[]>> relevantFutures = new ArrayList<>();
    List<String> hashList = getEffectiveNodes(key);
    String node = hashList.get(0);
    if (node == null) {
    } else {
      ChannelFuture cf = connectionPoolMgr.getNode(node);
      if (cf.channel().isWritable()) {
        UUID id = UUID.randomUUID();
        SettableFuture<byte[]> f = SettableFuture.create();
        Futures.withTimeout(f, TIMEOUT, TimeUnit.MILLISECONDS, evg);
        Futures.addCallback(f, new FutureCallback<byte[]>() {
          @Override
          public void onSuccess(@Nullable byte[] bytes) {
            if (relevantFutures.size() > 1) {
              relevantFutures.get(1).cancel(true);
            }
          }

          @Override
          public void onFailure(Throwable throwable) {

          }
        });
        futureMap.put(id, f);
        relevantFutures.add(f);
        cf.channel().writeAndFlush(new DefaultChicagoMessage(id, Op.STREAM, key, null, offset));
        connectionPoolMgr.releaseChannel(node, cf);

        evg.schedule(() -> {
          String node1 = hashList.get(1);
          if (node1 == null) {
          } else {
            ChannelFuture cf1 = null;
            try {
              cf1 = connectionPoolMgr.getNode(node1);
            } catch (ChicagoClientTimeoutException e) {
              e.printStackTrace();
            }
            if (cf1.channel().isWritable()) {
              UUID id1 = UUID.randomUUID();
              SettableFuture<byte[]> f1 = SettableFuture.create();
              Futures.withTimeout(f1, TIMEOUT, TimeUnit.MILLISECONDS, evg);
              Futures.addCallback(f1, new FutureCallback<byte[]>() {
                @Override
                public void onSuccess(@Nullable byte[] bytes) {

                }

                @Override
                public void onFailure(Throwable throwable) {

                }
              });
              futureMap.put(id1, f1);
              relevantFutures.add(f1);
              cf1.channel().writeAndFlush(new DefaultChicagoMessage(id1, Op.STREAM, key, null, offset));
              connectionPoolMgr.releaseChannel(node, cf1);
            }
          }
        }, 2, TimeUnit.MILLISECONDS);

        return Futures.successfulAsList(relevantFutures);
      }
    }
    return null;
  }

  public ListenableFuture<List<byte[]>> read(byte[] key) throws ChicagoClientTimeoutException {
    return read(ChiUtil.defaultColFam.getBytes(), key);
  }

  public ListenableFuture<List<byte[]>> read(byte[] colFam, byte[] key) throws ChicagoClientTimeoutException {
    ConcurrentLinkedDeque<byte[]> responseList = new ConcurrentLinkedDeque<>();
    List<ListenableFuture<byte[]>> relevantFutures = new ArrayList<>();
    List<String> hashList = getEffectiveNodes(colFam);
    String node = hashList.get(0);
    if (node == null) {
    } else {
      ChannelFuture cf = connectionPoolMgr.getNode(node);
      if (cf.channel().isWritable()) {
        UUID id = UUID.randomUUID();
        SettableFuture<byte[]> f = SettableFuture.create();
        Futures.withTimeout(f, TIMEOUT, TimeUnit.MILLISECONDS, evg);
        Futures.addCallback(f, new FutureCallback<byte[]>() {
          @Override
          public void onSuccess(@Nullable byte[] bytes) {
            if (relevantFutures.size() > 1) {
              relevantFutures.get(1).cancel(true);
            }
          }

          @Override
          public void onFailure(Throwable throwable) {

          }
        });
        futureMap.put(id, f);
        relevantFutures.add(f);
        cf.channel().writeAndFlush(new DefaultChicagoMessage(id, Op.READ, colFam, key, null));
        connectionPoolMgr.releaseChannel(node, cf);

        evg.schedule(() -> {
          String node1 = hashList.get(1);
          if (node1 == null) {
          } else {
            ChannelFuture cf1 = null;
            try {
              cf1 = connectionPoolMgr.getNode(node1);
            } catch (ChicagoClientTimeoutException e) {
              e.printStackTrace();
            }
            if (cf1.channel().isWritable()) {
              UUID id1 = UUID.randomUUID();
              SettableFuture<byte[]> f1 = SettableFuture.create();
              Futures.withTimeout(f1, TIMEOUT, TimeUnit.MILLISECONDS, evg);
              Futures.addCallback(f1, new FutureCallback<byte[]>() {
                @Override
                public void onSuccess(@Nullable byte[] bytes) {
                }

                @Override
                public void onFailure(Throwable throwable) {

                }
              });
              futureMap.put(id1, f1);
              relevantFutures.add(f1);
              cf1.channel().writeAndFlush(new DefaultChicagoMessage(id1, Op.READ, colFam, key, null));
              connectionPoolMgr.releaseChannel(node, cf1);
            }
          }
        }, 2, TimeUnit.MILLISECONDS);

        return Futures.successfulAsList(relevantFutures);
      }
    }
    return null;
  }

  public ListenableFuture<List<byte[]>> write(byte[] key, byte[] value) throws ChicagoClientTimeoutException, ChicagoClientException {
    return write(ChiUtil.defaultColFam.getBytes(), key, value);
  }

  public ListenableFuture<List<byte[]>> write(byte[] colFam, byte[] key, byte[] value) throws ChicagoClientTimeoutException, ChicagoClientException {
    return _write(colFam, key, value, 0);
  }

  private ListenableFuture<List<byte[]>> _write(byte[] colFam, byte[] key, byte[] value, int _retries) throws ChicagoClientTimeoutException, ChicagoClientException {
    final int retries = _retries;
    List<ListenableFuture<byte[]>> relevantFutures = new ArrayList<>();

    final long startTime = System.currentTimeMillis();
    List<String> hashList = getEffectiveNodes(colFam);
    for (String node : hashList) {
      if (node == null) {
      } else {
        ChannelFuture cf = connectionPoolMgr.getNode(node);
        if (cf.channel().isWritable()) {
          UUID id = UUID.randomUUID();
          SettableFuture<byte[]> f = SettableFuture.create();
          Futures.withTimeout(f, TIMEOUT, TimeUnit.MILLISECONDS, evg);
          Futures.addCallback(f, new FutureCallback<byte[]>() {
            @Override
            public void onSuccess(@Nullable byte[] bytes) {

            }

            @Override
            public void onFailure(Throwable throwable) {

            }
          });
          futureMap.put(id, f);
          relevantFutures.add(f);
          cf.channel().writeAndFlush(new DefaultChicagoMessage(id, Op.WRITE, colFam, key, value));
          connectionPoolMgr.releaseChannel(node, cf);
        }
      }
    }
    return Futures.successfulAsList(relevantFutures);
  }


  public ListenableFuture<List<byte[]>> tsWrite(byte[] key, byte[] value) throws ChicagoClientTimeoutException, ChicagoClientException {
    if (!streamBuffer.containsKey(new String(key))) {
      streamBuffer.put(new String(key), Unpooled.buffer());
    }

    ByteBuf bb = streamBuffer.get(new String(key));
    bb.writeBytes(value);
    bb.writeBytes("@@@".getBytes());

    if (bb.readableBytes() > 10000) {
      return _tsWrite(key, bb.array());
    } else {
      return SettableFuture.create();
    }
  }

  private ListenableFuture<List<byte[]>> _tsWrite(byte[] key, byte[] value) throws ChicagoClientTimeoutException, ChicagoClientException {
    return __tsWrite(null, key, value, 0);
  }

  private ListenableFuture<List<byte[]>> _tsWrite(byte[] colFam, byte[] key, byte[] value) throws ChicagoClientTimeoutException, ChicagoClientException {
    return __tsWrite(colFam, key, value, 0);
  }

  private ListenableFuture<List<byte[]>> __tsWrite(byte[] colFam, byte[] key, byte[] value, int _retries) throws ChicagoClientTimeoutException, ChicagoClientException {
    final int retries = _retries;
    List<ListenableFuture<byte[]>> relevantFutures = new ArrayList<>();
    final long startTime = System.currentTimeMillis();

    List<String> hashList;
    if (colFam == null) {
      hashList = getEffectiveNodes(key);
    } else {
      hashList = getEffectiveNodes(colFam);
    }
    for (String node : hashList) {
      if (node == null) {
      } else {
        ChannelFuture cf = connectionPoolMgr.getNode(node);
        if (cf.channel().isWritable()) {
          UUID id = UUID.randomUUID();
          SettableFuture<byte[]> f = SettableFuture.create();
          Futures.addCallback(f, new FutureCallback<byte[]>() {
            @Override
            public void onSuccess(@Nullable byte[] bytes) {

            }

            @Override
            public void onFailure(Throwable throwable) {

            }
          });
          futureMap.put(id, f);
          relevantFutures.add(f);
          if (colFam != null) {
            cf.channel().writeAndFlush(new DefaultChicagoMessage(id, Op.TS_WRITE, colFam, key, value));
          } else {
            cf.channel().writeAndFlush(new DefaultChicagoMessage(id, Op.TS_WRITE, key, null, value));
          }
          connectionPoolMgr.releaseChannel(node, cf);
        }
      }
    }
    return Futures.successfulAsList(relevantFutures);
  }

  public ListenableFuture<List<byte[]>> delete(byte[] key) throws ChicagoClientTimeoutException, ChicagoClientException {
    return delete(ChiUtil.defaultColFam.getBytes(), key);
  }

  public ListenableFuture<List<byte[]>> delete(byte[] colFam, byte[] key) throws ChicagoClientTimeoutException, ChicagoClientException {
    return _delete(colFam, key, 0);
  }

  public ListenableFuture<List<byte[]>> deleteColFam(byte[] colFam) throws ChicagoClientTimeoutException, ChicagoClientException {
    final int retries = 0;
    List<ListenableFuture<byte[]>> relevantFutures = new ArrayList<>();
    final long startTime = System.currentTimeMillis();
    List<String> hashList = getEffectiveNodes(colFam);
    for (String node : hashList) {
      if (node == null) {
      } else {
        ChannelFuture cf = connectionPoolMgr.getNode(node);
        if (cf.channel().isWritable()) {
          UUID id = UUID.randomUUID();
          SettableFuture<byte[]> f = SettableFuture.create();
          Futures.addCallback(f, new FutureCallback<byte[]>() {
            @Override
            public void onSuccess(@Nullable byte[] bytes) {

            }

            @Override
            public void onFailure(Throwable throwable) {

            }
          });
          futureMap.put(id, f);
          relevantFutures.add(f);
          cf.channel().writeAndFlush(new DefaultChicagoMessage(id, Op.DELETE, colFam, null, null));
          connectionPoolMgr.releaseChannel(node, cf);
        }
      }
    }
    return Futures.successfulAsList(relevantFutures);
  }

  private ListenableFuture<List<byte[]>> _delete(byte[] colFam, byte[] key, int _retries) throws ChicagoClientTimeoutException, ChicagoClientException {
    final int retries = _retries;
    List<ListenableFuture<byte[]>> relevantFutures = new ArrayList<>();
    final long startTime = System.currentTimeMillis();
    List<String> hashList = getEffectiveNodes(colFam);
    for (String node : hashList) {
      if (node == null) {
      } else {
        ChannelFuture cf = connectionPoolMgr.getNode(node);
        if (cf.channel().isWritable()) {
          UUID id = UUID.randomUUID();
          SettableFuture<byte[]> f = SettableFuture.create();
          Futures.addCallback(f, new FutureCallback<byte[]>() {
            @Override
            public void onSuccess(@Nullable byte[] bytes) {

            }

            @Override
            public void onFailure(Throwable throwable) {

            }
          });
          futureMap.put(id, f);
          relevantFutures.add(f);
          cf.channel().writeAndFlush(new DefaultChicagoMessage(id, Op.DELETE, colFam, key, null));
          connectionPoolMgr.releaseChannel(node, cf);
        }
      }
    }
    return Futures.successfulAsList(relevantFutures);
  }
}
