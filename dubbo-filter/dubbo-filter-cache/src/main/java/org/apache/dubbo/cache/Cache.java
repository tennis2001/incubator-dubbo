/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.cache;

/**
 * Cache interface to support storing and retrieval of value against a lookup key. It has two operation <b>get</b> and <b>put</b>.
 * <li><b>put</b>-Storing value against a key.</li>
 * <li><b>get</b>-Retrieval of object.</li>
 * @see org.apache.dubbo.cache.support.lru.LruCache
 * @see org.apache.dubbo.cache.support.jcache.JCache
 * @see org.apache.dubbo.cache.support.expiring.ExpiringCache
 * @see org.apache.dubbo.cache.support.threadlocal.ThreadLocalCache
 */
//Cache有多种形式，接口cache统一上层形式（不同的cache策略有不同的配置信息）
//cache 数据结构 和 创建方法 分别走两条路，Cache接口统一管理 cache 数据结构，CacheFactory接口统一管理 cache创建方法
//为什么要用Factory管理cache的创建，而不是直接创建cache？可以直接创建cache吗
public interface Cache {
    /**
     * API to store value against a key
     * @param key  Unique identifier for the object being store.
     * @param value Value getting store
     */
    void put(Object key, Object value);

    /**
     * API to return stored value using a key.
     * @param key Unique identifier for cache lookup
     * @return Return stored object against key
     */
    Object get(Object key);

}
