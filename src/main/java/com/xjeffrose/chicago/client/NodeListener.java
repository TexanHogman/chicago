package com.xjeffrose.chicago.client;

/**
 * Created by smadan on 8/17/16.
 */
public interface NodeListener<T> {
  public void nodeAdded(T node);

  public void nodeRemoved(T node);
}
