package com.hmdp.utils;

public interface ILock {

    boolean tryLock(Long timeout);

    void releaseLock();


}
