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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.*;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {
    //类成员变量
    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    //有些库函数（library function）却要求应用先传给它一个函数，好在合适的时候调用，以完成目标任务。
    // 这个被传入的、后又被调用的函数就称为回调函数（callback function）
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static DubboProtocol INSTANCE;

    //实例成员变量

    //ConcurrentHashMap<String, ExchangeServer> serverMap保存 所有服务器
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<String, ExchangeServer>(); // <host:port,Exchanger>
    //引用计数的客户端  引用计数干啥的？？？
    private final Map<String, ReferenceCountExchangeClient> referenceClientMap = new ConcurrentHashMap<String, ReferenceCountExchangeClient>(); // <host:port,Exchanger>
    //ghostClientMap 存的是什么？？？
    private final ConcurrentMap<String, LazyConnectExchangeClient> ghostClientMap = new ConcurrentHashMap<String, LazyConnectExchangeClient>();
    //locks 锁什么？？
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private final Set<String> optimizers = new ConcurrentHashSet<String>();
    //consumer side export a stub service for dispatching event
    //servicekey-stubmethods
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<String, String>();

    //为什么要用匿名内部类？
    //创建ExchangeHandlerAdapter对象，内部重写了reply、received、connected、disconnected、invoke、createInvocation方法
    //匿名内部类 继承/实现 了ExchangeHandlerAdapter
    //创建了一个继承自了ExchangeHandlerAdapter的匿名类的对象，自动向上转型
    // ExchangeHandlerAdapter 继承了 TelnetHandlerAdapter 实现了 ExchangeHandler
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        //CompletableFuture与线程有关，与执行结果有关
        //ExchangeChannel好像是服务请求的统一管理；Invocation好像是方法调用具体信息
        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {
            //如果message是Invocation实例
            if (message instanceof Invocation) {
                Invocation inv = (Invocation) message;
                //获取Invoker调用者（ExchangeChannel好像是服务请求的统一管理；Invocation好像是方法调用具体信息）
                Invoker<?> invoker = getInvoker(channel, inv);
                // need to consider backward-compatibility if it's a callback
                //callback是什么意思？callback服务？
                // Boolean.TRUE.toString().equals（）这种写法！！！而不用 someCondition.equals("true")
                if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                    //从invoker服务调用者获取methods信息-->invoker可以实现的方法
                    String methodsStr = invoker.getUrl().getParameters().get("methods");
                    boolean hasMethod = false;
                    //methodsStr为空或一个
                    if (methodsStr == null || !methodsStr.contains(",")) {
                        //从Invocation中判断是否有相同Method
                        //为什么判断Invocation有没有该方法，而不是判断invoker是不是支持invocation中的方法？
                        hasMethod = inv.getMethodName().equals(methodsStr);
                    }
                    //分别判断多个method
                    else {
                        String[] methods = methodsStr.split(",");
                        for (String method : methods) {
                            //从Invocation中判断是否有相同Method
                            if (inv.getMethodName().equals(method)) {
                                hasMethod = true;
                                break;
                            }
                        }
                    }
                    //如果没有相应method（invoker和invocation中的方法无法匹配）
                    //invocation里的方法在callback服务接口中找不到，无法invoke
                    //更新api的接口,api的接口是指invoker的url信息吗？
                    if (!hasMethod) {
                        logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                                + " not found in callback service interface ,invoke will be ignored."
                                + " please update the api interface. url is:"
                                + invoker.getUrl()) + " ,invocation is :" + inv);
                        return null;
                    }
                }
                // 从本地InternalThreadLocal获取Rpc上下文
                RpcContext rpcContext = RpcContext.getContext();
                //判断invoker是否支持（invocation里该方法）异步
                boolean supportServerAsync = invoker.getUrl().getMethodParameter(inv.getMethodName(), Constants.ASYNC_KEY, false);
                //如果支持异步（serverAsync是指服务器异步吗？）
                if (supportServerAsync) {
                    //创建 CompletableFuture 完成的结果
                    CompletableFuture<Object> future = new CompletableFuture<>();
                    //rpcContext设置为 异步context
                    //AsyncContextImpl 异步context实现
                    rpcContext.setAsyncContext(new AsyncContextImpl(future));
                }
                //从ExchangeChannel获取RemoteAddress remoteAddress指的是？调用端还是服务端？
                rpcContext.setRemoteAddress(channel.getRemoteAddress());
                //调用者invoker调用inv（invocation中是方法调用的信息），相当于 invoker调用一个方法
                Result result = invoker.invoke(inv);

                //如果调用结果是异步的
                if (result instanceof AsyncRpcResult) {
                    //thenApply()方法  参数是一个function？
                    // thenApply(r -> (Object) r)？
                    return ((AsyncRpcResult) result).getResultFuture().thenApply(r -> (Object) r);
                }
                //不是异步结果 直接完成
                else {
                    return CompletableFuture.completedFuture(result);
                }
            }
            //channel.getRemoteAddress代表是 consumer！！！
            //channel.getLocalAddress代表是 provider！！！
            throw new RemotingException(channel, "Unsupported request: "
                    + (message == null ? null : (message.getClass().getName() + ": " + message))
                    + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
        }

        //received 与 reply 有什么区别？
        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            //如果message是Invocation的实例
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);
            }
            //如果不是Invocation的实例
            else {
                //super.received没有方法体？？？为什么这么写？
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, Constants.ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isInfoEnabled()) {
                logger.info("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, Constants.ON_DISCONNECT_KEY);
        }

        //invoke 实现 connect 和 disconnect 动作
        private void invoke(Channel channel, String methodKey) {
            //创建一个Invocation (一次方法调用的描述)
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            //得到methodKey所对应的方法
            String method = url.getParameter(methodKey);
            //没有方法，直接返回
            if (method == null || method.length() == 0) {
                return null;
            }
            //创建RpcInvocation  RpcInvocation 实现了 Invocation
            // Class<?>[0] Object[0]是什么意思？ Class[0] 类型信息数组大小为0？
            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            //从URL中得到方法的各种详细信息  设置为attachment
            invocation.setAttachment(Constants.PATH_KEY, url.getPath());
            invocation.setAttachment(Constants.GROUP_KEY, url.getParameter(Constants.GROUP_KEY));
            invocation.setAttachment(Constants.INTERFACE_KEY, url.getParameter(Constants.INTERFACE_KEY));
            invocation.setAttachment(Constants.VERSION_KEY, url.getParameter(Constants.VERSION_KEY));
            //STUB_EVENT_KEY是什么意思？
            if (url.getParameter(Constants.STUB_EVENT_KEY, false)) {
                invocation.setAttachment(Constants.STUB_EVENT_KEY, Boolean.TRUE.toString());
            }
            return invocation;
        }
    };

    //this指的是？
    public DubboProtocol() {
        INSTANCE = this;
    }

    //静态方法
    public static DubboProtocol getDubboProtocol() {
        //如果没有instance(注意instance是static)
        if (INSTANCE == null) {
            //ExtensionLoader扩展加载器，加载Protocol.class，得到DubboProtocol扩展
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME); // load
        }
        //返回DubboProtocol实例 INSTANCE
        return INSTANCE;
    }

    //获取服务器
    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    //获取导出者？Exporter干啥的？？？
    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    //是客户端吗
    private boolean isClientSide(Channel channel) {
        //获取远程地址 consumer？
        InetSocketAddress address = channel.getRemoteAddress();
        //URL是谁的URL?
        URL url = channel.getUrl();
        //URL的端口 ==远程地址的端口 && URL的IP==远程地址的host地址
        //如果返回1，说明是 客户端
        //那么，反推过来，URL应该是客户端的URL
        //为什么要这么比较，Channel里getRemoteAddress、getUrl()到底要干啥？？？
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    //获取调用者Invoker
    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        //callBack干啥的？？？
        boolean isCallBackServiceInvoke = false;
        //stub干啥的？？？
        boolean isStubServiceInvoke = false;
        //localAddress provider的地址？
        int port = channel.getLocalAddress().getPort();
        //从Invocation中获取path
        String path = inv.getAttachments().get(Constants.PATH_KEY);
        // if it's callback service on client side 这个注释？？？
        //如果是stub
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(Constants.STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            //remoteAddress consumer的地址？
            port = channel.getRemoteAddress().getPort();
        }
        //callback
        //是客户端，但不是stub
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        //从invocation得到path和callback 干啥的？？？
        if (isCallBackServiceInvoke) {
            path = inv.getAttachments().get(Constants.PATH_KEY) + "." + inv.getAttachments().get(Constants.CALLBACK_SERVICE_KEY);
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }
        //服务的关键字？？？
        String serviceKey = serviceKey(port, path, inv.getAttachments().get(Constants.VERSION_KEY), inv.getAttachments().get(Constants.GROUP_KEY));

        //从所有的Exporter Exporter中获取该serviceKey对应的DubboExporter
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        //如果找不到
        if (exporter == null) {
            //路由异常
            //找不到导出服务  版本、组别不匹配？
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);
        }

        //从exporter获取所有Invoker
        return exporter.getInvoker();
    }

    //获取所有Invoker
    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    //默认端口有什么用？
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    //导出Exporter
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        //获取调用者invoker的URL
        URL url = invoker.getUrl();

        // export service.
        // 获取服务标识，理解成服务坐标也行。由服务组名，服务名，服务版本号以及端口组成。比如：
        // demoGroup/com.alibaba.dubbo.demo.DemoService:1.0.1:20880
        String key = serviceKey(url);
        // 创建 DubboExporter
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        // 将该调用者invoker对应的 <key, exporter> 键值对放入缓存中
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        // 本地存根相关代码 stub存根？？？啥意思
        //stub 和 callback ?干啥的？？？
        Boolean isStubSupportEvent = url.getParameter(Constants.STUB_EVENT_KEY, Constants.DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(Constants.IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(Constants.STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    //invoker的URL中 interface参数---对应 consumer
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(Constants.INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }
            } else {
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }
        // 启动服务器（传入invoker的URL）
        openServer(url);
        // 优化序列化
        optimizeSerialization(url);
        return exporter;
    }

    private void openServer(URL url) {
        // find server.
        // 获取 host:port，并将其作为服务器实例的 key，用于标识当前的服务器实例
        String key = url.getAddress();
        //client can export a service which's only for server to invoke
        boolean isServer = url.getParameter(Constants.IS_SERVER_KEY, true);
        //服务器
        if (isServer) {
            // 访问缓存
            ExchangeServer server = serverMap.get(key);
            //为什么这里用双重检查？？？多线程？？？
            if (server == null) {
                // 创建服务器实例
                synchronized (this) {
                    server = serverMap.get(key);
                    if (server == null) {
                        serverMap.put(key, createServer(url));
                    }
                }
            } else {
                // server supports reset, use together with override
                // 服务器已创建，则根据 url 中的配置重置服务器
                server.reset(url);
            }
        }
    }

    private ExchangeServer createServer(URL url) {
        // send readonly event when server closes, it's enabled by default
        //设置为channel readonly？
        url = url.addParameterIfAbsent(Constants.CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString());
        // enable heartbeat by default
        // 添加心跳检测配置到 url 中
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));
        // 获取 server 参数，默认为 netty
        String str = url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_SERVER);

        // 通过 SPI 检测是否存在 server 参数所代表的 Transporter 拓展，不存在则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }
        // 添加编码解码器参数
        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);
        ExchangeServer server;
        try {
            // 创建 ExchangeServer
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }
        // 获取 client 参数，可指定 netty，mina
        str = url.getParameter(Constants.CLIENT_KEY);
        if (str != null && str.length() > 0) {
            // 获取所有的 Transporter 实现类名称集合，比如 supportedTypes = [netty, mina]
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            // 检测当前 Dubbo 所支持的 Transporter 实现类名称列表中，
            // 是否包含 client 所表示的 Transporter，若不包含，则抛出异常
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        return server;
    }

    private void optimizeSerialization(URL url) throws RpcException {
        String className = url.getParameter(Constants.OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            //让当前线程加载该类型
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);

            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            //创建该类型的实例
            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            //SerializableClassRegistry管理SerializableClasses
            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);
        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);
        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    //Invoker 是 Dubbo 的核心模型，代表一个可执行体。
    // 在服务提供方，Invoker 用于调用服务提供类。
    // 在服务消费方，Invoker 用于执行远程调用。Invoker 是由 Protocol 实现类构建而来。
    // Protocol 实现类有很多，本节会分析最常用的两个，分别是 RegistryProtocol 和 DubboProtocol
    @Override
    public <T> Invoker<T> refer(Class<T> serviceType, URL url) throws RpcException {
        optimizeSerialization(url);
        // create rpc invoker.
        // 创建 DubboInvoker
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        invokers.add(invoker);
        return invoker;
    }

    //这个方法用于获取客户端实例，实例类型为 ExchangeClient。
    // ExchangeClient 实际上并不具备通信能力，它需要基于更底层的客户端实例进行通信。
    // 比如 NettyClient、MinaClient 等，默认情况下，Dubbo 使用 NettyClient 进行通信。
    private ExchangeClient[] getClients(URL url) {
        // whether to share connection
        // 是否共享连接
        boolean service_share_connect = false;
        // 获取连接数，默认为0，表示未配置（如果没配置0会怎样？？？）
        int connections = url.getParameter(Constants.CONNECTIONS_KEY, 0);
        // if not configured, connection is shared, otherwise, one connection for one service
        // 如果未配置 connections，则共享连接
        if (connections == 0) {
            service_share_connect = true;
            connections = 1;
        }

        //连接数 与 客户端实例个数 对应？
        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            if (service_share_connect) {
                // 获取共享客户端
                clients[i] = getSharedClient(url);
            } else {
                // 初始化新的客户端
                clients[i] = initClient(url);
            }
        }
        return clients;
    }

    /**
     * Get shared connection
     */
    private ExchangeClient getSharedClient(URL url) {
        String key = url.getAddress();
        // 获取带有“引用计数”功能的 ExchangeClient
        ReferenceCountExchangeClient client = referenceClientMap.get(key);
        //存在“引用计数”功能的 ExchangeClient
        if (client != null) {
            //该客户端没有关闭
            if (!client.isClosed()) {
                // 增加引用计数（相当于该客户端多了一个引用，即共享的）
                client.incrementAndGetCount();
                return client;
            } else {
                //该客户端已经关闭，移除这个客户端
                referenceClientMap.remove(key);
            }
        }

        //没有“引用计数”功能的 ExchangeClient
        //locks记录了什么？？
        locks.putIfAbsent(key, new Object());
        synchronized (locks.get(key)) {
            //先访问缓存，若缓存未命中，则通过 initClient 方法创建新的 ExchangeClient 实例
            // 并将该实例传给 ReferenceCountExchangeClient 构造方法创建一个带有引用计数功能的 ExchangeClient 实例
            //上面的代码说明 没有“引用计数”功能的 ExchangeClient referenceClientMap.get(key)为空
            //referenceClientMap.containsKey(key) containsKey有什么作用？代码写错了？？？
            if (referenceClientMap.containsKey(key)) {
                return referenceClientMap.get(key);
            }

            // 创建 ExchangeClient 客户端
            ExchangeClient exchangeClient = initClient(url);
            // 将 ExchangeClient 实例传给 ReferenceCountExchangeClient，这里使用了装饰模式
            //装饰模式？？？
            client = new ReferenceCountExchangeClient(exchangeClient, ghostClientMap);
            referenceClientMap.put(key, client);
            ghostClientMap.remove(key);
            locks.remove(key);
            return client;
        }
    }

    /**
     * Create new connection 创建新的连接，初始化客户端
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.
        // 获取客户端类型，默认为 netty
        String str = url.getParameter(Constants.CLIENT_KEY, url.getParameter(Constants.SERVER_KEY, Constants.DEFAULT_REMOTING_CLIENT));

        // 添加编解码和心跳包参数到 url 中
        url = url.addParameter(Constants.CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default
        url = url.addParameterIfAbsent(Constants.HEARTBEAT_KEY, String.valueOf(Constants.DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        // 检测客户端类型是否存在，不存在则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            // 获取 lazy 配置，并根据配置值决定创建的客户端类型
            if (url.getParameter(Constants.LAZY_CONNECT_KEY, false)) {
                // 创建懒加载 ExchangeClient 实例
                client = new LazyConnectExchangeClient(url, requestHandler);
            } else {
                // 创建普通 ExchangeClient 实例
                client = Exchangers.connect(url, requestHandler);
            }
        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }
        return client;
    }

    @Override
    public void destroy() {
        for (String key : new ArrayList<String>(serverMap.keySet())) {
            //从serverMap移除该server
            ExchangeServer server = serverMap.remove(key);
            if (server != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo server: " + server.getLocalAddress());
                    }
                    server.close(ConfigurationUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        for (String key : new ArrayList<String>(referenceClientMap.keySet())) {
            ExchangeClient client = referenceClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close(ConfigurationUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }

        for (String key : new ArrayList<String>(ghostClientMap.keySet())) {
            ExchangeClient client = ghostClientMap.remove(key);
            if (client != null) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
                    }
                    client.close(ConfigurationUtils.getServerShutdownTimeout());
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        stubServiceMethodsMap.clear();
        super.destroy();
    }
}
