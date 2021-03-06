package wang.ismy.push.connector.service;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import io.netty.channel.Channel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import wang.ismy.push.common.entity.ServerMessage;
import wang.ismy.push.common.enums.ServerMessageTypeEnum;
import wang.ismy.push.connector.MessageConfirmDao;
import wang.ismy.push.connector.MessageDao;
import wang.ismy.push.connector.entity.MessageConfirmDO;
import wang.ismy.push.connector.entity.MessageDO;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;


/**
 * @author MY
 * @date 2020/6/17 20:01
 */
@Service
@Slf4j
@AllArgsConstructor
public class MessageService {
    private final ClientService clientService;
    private final RabbitTemplate rabbitTemplate;
    private final MessageConfirmDao messageConfirmDao;
    private final MessageDao messageDao;
    private Set<Long> messageSet = new ConcurrentSkipListSet<>();
    @PostConstruct
    public void init() throws IOException {
        var channel = rabbitTemplate.getConnectionFactory().createConnection().createChannel(true);
        String exchange = "message";
        channel.exchangeDeclare(exchange, "fanout",true);
        String queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, exchange, "");
        channel.basicConsume(queueName,false,new DefaultConsumer(channel){
            @Override
            public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) throws IOException {
                try {
                    if (messageSet.contains(envelope.getDeliveryTag())){
                        log.warn("消息:{}已处理过",envelope.getDeliveryTag());
                        return;
                    }
                    ServerMessage o = (ServerMessage) new ObjectInputStream(new ByteArrayInputStream(body)).readObject();
                    onMessage(o,envelope.getDeliveryTag());
                    channel.basicAck(envelope.getDeliveryTag(),false);
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public void read(Channel channel, byte[] bytes){
        String tmp = new String(bytes);
        tmp = tmp.trim();
        if (tmp.startsWith("heartbeat")){
            tmp = tmp.replaceAll("heartbeat-","");
            clientService.flushClientLiveTime(channel,tmp);
        }else if (tmp.startsWith("confirm")){
            tmp = tmp.replaceAll("confirm-","");
            String client = clientService.getClient(channel);
            if (client != null){
                messageConfirmDao.addMessageConfirm(tmp,client);
            }
        }
    }

    public void onMessage(ServerMessage serverMessage, Long tag) throws IOException {
        log.info("get message:{}",serverMessage);
        if (serverMessage.getMessageType().equals(ServerMessageTypeEnum.BROADCAST_MESSAGE_TYPE)){
            clientService.broadcast(new String(serverMessage.getPayload()));
            log.info("广播消息已发起");
        }else if(serverMessage.getMessageType().equals(ServerMessageTypeEnum.SINGLE_MESSAGE_TYPE)) {
            clientService.sendMessage(serverMessage.getTo(),new String(serverMessage.getPayload()));
            log.info("已向{}投递消息{}",serverMessage.getTo(),new String(serverMessage.getPayload()));
        }else if (serverMessage.getMessageType().equals(ServerMessageTypeEnum.KICK_OUT_MESSAGE_TYPE)){
            Channel channel = clientService.getClient(serverMessage.getTo());
            if (channel == null) {
                log.warn("关闭消息 找不到此用户：{}",serverMessage.getTo());
            }else {
                clientService.sendMessage(serverMessage.getTo(),"kickout");
                channel.close();
                log.info("断开客户 {} channel", serverMessage.getTo());
            }
        }
        messageSet.add(tag);
    }

    /**
     * 定时任务 负责对发送时间在15分钟内的消息 没有接受到的客户端进行消息重试
     */
    @Scheduled(fixedDelay = 5*1000)
    @Async
    public void retrySendMessage(){
        List<MessageDO> list = messageDao.getLast15MinutesMessage();
        log.info("最近 15 分钟 消息数:{}",list.size());
        for (MessageDO messageDO : list) {
            String messageType = StringUtils.isEmpty(messageDO.getMessageTarget()) ? "广播消息" : "对点消息";
            boolean isBroadcast = StringUtils.isEmpty(messageDO.getMessageTarget());
            log.info("消息 {} 为 {}",messageDO.getMessageId(), messageType);
            List<MessageConfirmDO> confirmList = messageConfirmDao.getByMessageId(messageDO.getMessageId());
            log.info("接收到消息 {} 的用户有 {}",messageDO.getMessageId(),confirmList.size());

            if(isBroadcast){
                retryBroadcast(messageDO,confirmList);
            }else{
                retryPeerMessage(messageDO,confirmList);
            }
        }
    }

    private void retryBroadcast(MessageDO messageDO,List<MessageConfirmDO> clientList){
        // 对当前在线用户与消息确认用户取差集
        Collection<String> onlineClients = clientService.getClients();
        var needsRetryList = onlineClients.stream().filter(online->
            clientList.stream().noneMatch(confirm->confirm.getMessageTarget().equals(online))
        ).collect(Collectors.toList());

        log.info("广播消息 {} 需要重试客户数量:{}",messageDO.getMessageId(),needsRetryList.size());
        for (String s : needsRetryList) {
            clientService.sendMessage(s,messageDO.getMessageContent());
        }
    }

    private void retryPeerMessage(MessageDO messageDO,List<MessageConfirmDO> clientList){
        // 消息确认列表不为空 表明客户已经收到消息
        if (!clientList.isEmpty()){
            return;
        }

        log.info("对客户 {} 进行重试消息 {}", messageDO.getMessageTarget(),messageDO.getMessageId());
        clientService.sendMessage(messageDO.getMessageTarget(),messageDO.getMessageContent());

    }
}
