package com.qianzhui.enode.rocketmq.command;

import com.alibaba.rocketmq.common.message.Message;
import com.alibaba.rocketmq.common.message.MessageConst;
import com.qianzhui.enode.commanding.*;
import com.qianzhui.enode.common.container.GenericTypeLiteral;
import com.qianzhui.enode.common.container.ObjectContainer;
import com.qianzhui.enode.common.io.AsyncTaskResult;
import com.qianzhui.enode.common.io.AsyncTaskStatus;
import com.qianzhui.enode.common.logging.ILogger;
import com.qianzhui.enode.common.logging.ILoggerFactory;
import com.qianzhui.enode.common.serializing.IJsonSerializer;
import com.qianzhui.enode.common.utilities.BitConverter;
import com.qianzhui.enode.common.utilities.Ensure;
import com.qianzhui.enode.infrastructure.ITypeNameProvider;
import com.qianzhui.enode.infrastructure.WrappedRuntimeException;
import com.qianzhui.enode.rocketmq.ITopicProvider;
import com.qianzhui.enode.rocketmq.RocketMQMessageTypeCode;
import com.qianzhui.enode.rocketmq.SendQueueMessageService;
import com.qianzhui.enode.rocketmq.TopicTagData;
import com.qianzhui.enode.rocketmq.client.Producer;

import javax.inject.Inject;
import java.net.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Created by junbo_xu on 2016/3/1.
 */
public class CommandService implements ICommandService {
    private ILogger _logger;
    private IJsonSerializer _jsonSerializer;
    private ITopicProvider<ICommand> _commandTopicProvider;
    private ITypeNameProvider _typeNameProvider;
    private ICommandRoutingKeyProvider _commandRouteKeyProvider;
    private SendQueueMessageService _sendMessageService;
    private CommandResultProcessor _commandResultProcessor;
    private Producer _producer;
    private ICommandKeyProvider _commandKeyProvider;

    @Inject
    public CommandService(CommandResultProcessor commandResultProcessor, Producer producer) {
        super();
        _commandResultProcessor = commandResultProcessor;
        _producer = producer;
        _jsonSerializer = ObjectContainer.resolve(IJsonSerializer.class);
        _commandTopicProvider = ObjectContainer.resolve(new GenericTypeLiteral<ITopicProvider<ICommand>>() {
        });
        _typeNameProvider = ObjectContainer.resolve(ITypeNameProvider.class);
        _commandRouteKeyProvider = ObjectContainer.resolve(ICommandRoutingKeyProvider.class);
        _sendMessageService = new SendQueueMessageService();
        _logger = ObjectContainer.resolve(ILoggerFactory.class).create(CommandService.class);
        _commandKeyProvider = new CommandKeyProvider();
    }

    public CommandService start() {
        if (_commandResultProcessor != null) {
            _commandResultProcessor.start();
        }
        return this;
    }

    public CommandService shutdown() {
        if (_commandResultProcessor != null) {
            _commandResultProcessor.shutdown();
        }
        return this;
    }

    @Override
    public void send(ICommand command) {
        _sendMessageService.sendMessage(_producer, buildCommandMessage(command, false), _commandRouteKeyProvider.getRoutingKey(command), command.id(), null);
    }

    @Override
    public CompletableFuture<AsyncTaskResult> sendAsync(ICommand command) {
        try {
            return _sendMessageService.sendMessageAsync(_producer, buildCommandMessage(command, false), _commandRouteKeyProvider.getRoutingKey(command), command.id(), null);
        } catch (Exception ex) {
            return CompletableFuture.completedFuture(new AsyncTaskResult<>(AsyncTaskStatus.Failed, ex.getMessage()));
        }
    }

    @Override
    public CompletableFuture<AsyncTaskResult> sendAsyncAll(ICommand... commands) {
        Optional<CompletableFuture<AsyncTaskResult>> reduce = Arrays.asList(commands).stream()
                .map(this::sendAsync)
                .reduce((result, current) ->
                        result.thenCombine(current, CommandService::combine)
                );

        return reduce.get();
    }

    public static AsyncTaskResult combine(AsyncTaskResult r1, AsyncTaskResult r2) {
        Set<AsyncTaskResult> totalResult = new HashSet<>();
        totalResult.add(r1);
        totalResult.add(r2);

        List<AsyncTaskResult> failedResults = totalResult.stream().filter(task -> task.getStatus() == AsyncTaskStatus.Failed).collect(Collectors.toList());
        if (failedResults.size() > 0) {
            return new AsyncTaskResult(AsyncTaskStatus.Failed, String.join("|", failedResults.stream().map(AsyncTaskResult::getErrorMessage).collect(Collectors.toList())));
        }

        List<AsyncTaskResult> ioExceptionResults = totalResult.stream().filter(task -> task.getStatus() == AsyncTaskStatus.IOException).collect(Collectors.toList());
        if (ioExceptionResults.size() > 0) {
            return new AsyncTaskResult(AsyncTaskStatus.IOException, String.join("|", ioExceptionResults.stream().map(AsyncTaskResult::getErrorMessage).collect(Collectors.toList())));
        }

        return AsyncTaskResult.Success;
    }

    @Override
    public CommandResult execute(ICommand command, int timeoutMillis) {
        try {
            AsyncTaskResult<CommandResult> result = executeAsync(command).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return result.getData();
        } catch (TimeoutException e) {
            throw new CommandExecuteTimeoutException(String.format("Command execute timeout, commandId: %s, aggregateRootId: %s", command.id(), command.getAggregateRootId()));
        } catch (Exception e) {
            throw new WrappedRuntimeException(e);
        }
    }

    @Override
    public CommandResult execute(ICommand command, CommandReturnType commandReturnType, int timeoutMillis) {
        try {
            AsyncTaskResult<CommandResult> result = executeAsync(command, commandReturnType).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return result.getData();
        } catch (TimeoutException e) {
            throw new CommandExecuteTimeoutException(String.format("Command execute timeout, commandId: %s, aggregateRootId: %s", command.id(), command.getAggregateRootId()));
        } catch (Exception e) {
            throw new WrappedRuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<AsyncTaskResult<CommandResult>> executeAsync(ICommand command) {
        return executeAsync(command, CommandReturnType.CommandExecuted);
    }

    @Override
    public CompletableFuture<AsyncTaskResult<CommandResult>> executeAsync(ICommand command, CommandReturnType commandReturnType) {
        try {
            Ensure.notNull(_commandResultProcessor, "commandResultProcessor");

            CompletableFuture<AsyncTaskResult<CommandResult>> taskCompletionSource = new CompletableFuture<>();
            _commandResultProcessor.registerProcessingCommand(command, commandReturnType, taskCompletionSource);

            CompletableFuture<AsyncTaskResult> sendMessageAsync = _sendMessageService.sendMessageAsync(_producer, buildCommandMessage(command, true), _commandKeyProvider.getKey(command), command.id(), null);
            sendMessageAsync.thenAccept(sendResult -> {
                if (sendResult.getStatus().equals(AsyncTaskStatus.Success)) {
                    //_commandResultProcessor中会继续等命令或事件处理完成的状态
                } else {
                    //TODO 是否删除下面一行代码
                    taskCompletionSource.complete(new AsyncTaskResult<>(sendResult.getStatus(), sendResult.getErrorMessage()));
                    _commandResultProcessor.processFailedSendingCommand(command);
                }
            });

            return taskCompletionSource;
        } catch (Exception ex) {
            return CompletableFuture.completedFuture(new AsyncTaskResult<>(AsyncTaskStatus.Failed, ex.getMessage()));
        }
    }

    private Message buildCommandMessage(ICommand command, boolean needReply) {
        Ensure.notNull(command.getAggregateRootId(), "aggregateRootId");
        String commandData = _jsonSerializer.serialize(command);
        TopicTagData topicTagData = _commandTopicProvider.getPublishTopic(command);
        String replyAddress = needReply && _commandResultProcessor != null ? parseAddress(_commandResultProcessor.getBindingAddress()) : null;
//        String replyAddress = null;
        String messageData = _jsonSerializer.serialize(new CommandMessage(commandData, replyAddress, command.getClass().getName()));

        byte[] body = BitConverter.getBytes(messageData);

        String key = buildRocketMQMessageKey(command);

        Message message = new Message(topicTagData.getTopic(),
                topicTagData.getTag(),
                key,
                RocketMQMessageTypeCode.CommandMessage.ordinal(), body, true);

        if (command.getStartDeliverTime() > 0) {
            message.putUserProperty(RocketMQSystemPropKey.STARTDELIVERTIME, String.valueOf(command.getStartDeliverTime()));
        }

        return message;
    }

    private String buildRocketMQMessageKey(ICommand command) {
        return String.format("%s%s",
                command.id(), //命令唯一id
                command.getAggregateRootId() == null ? "" : MessageConst.KEY_SEPARATOR + "cmd_agg_" + command.getAggregateRootId() //聚合根id
        );
    }

    static public class RocketMQSystemPropKey {
        public static final String TAG = "__TAG";
        public static final String KEY = "__KEY";
        public static final String MSGID = "__MSGID";
        public static final String RECONSUMETIMES = "__RECONSUMETIMES";
        /**
         * 设置消息的定时投递时间（绝对时间),最大延迟时间为7天.
         * <p>例1: 延迟投递, 延迟3s投递, 设置为: System.currentTimeMillis() + 3000;
         * <p>例2: 定时投递, 2016-02-01 11:30:00投递, 设置为: new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2016-02-01 11:30:00").getTime()
         */
        public static final String STARTDELIVERTIME = "__STARTDELIVERTIME";
    }

    private String parseAddress(SocketAddress address) {
        if (address instanceof InetSocketAddress) {
            InetSocketAddress socketAddress = (InetSocketAddress) address;
            int port = socketAddress.getPort();

            InetAddress localAddress = socketAddress.getAddress();

            if (!isSiteLocalAddress(localAddress)) {
                try {
                    localAddress = getIp4LocalAddress();
                } catch (UnknownHostException e) {
                    throw new WrappedRuntimeException("No local address found", e);
                }
            }
            return String.format("%s:%d", localAddress.getHostAddress(), port);
        } else {
            throw new RuntimeException("Unknow socket address:" + address);
        }
    }

    private InetAddress getIp4LocalAddress() throws UnknownHostException {
        return Inet4Address.getLocalHost();
        /*Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        while(networkInterfaces.hasMoreElements()){
            NetworkInterface nextElement = networkInterfaces.nextElement();

            Enumeration<InetAddress> inetAddresses = nextElement.getInetAddresses();
            while(inetAddresses.hasMoreElements()){
                InetAddress inetAddress = inetAddresses.nextElement();
                if(isSiteLocalAddress(inetAddress))
                    return inetAddress;
            }
        }

        return null;*/
    }

    private boolean isSiteLocalAddress(InetAddress address) {
        return address.isSiteLocalAddress() && !address.isLoopbackAddress() && !address.getHostAddress().contains(":");
    }
}
