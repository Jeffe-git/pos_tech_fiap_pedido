package br.com.postechfiap.jlapppedido.infra.config.mq;

import org.springframework.stereotype.Component;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@AllArgsConstructor
@Slf4j
public class SolicitacoesPedidosSubscriber {

  // @Autowired
  // private SimpMessagingTemplate template;
  //
  // @RabbitListener(queues = "${mq.queues.pedidos}", id = "totem-pedido")
  // public void receive(String message) {
  // ObjectMapper mapper = new ObjectMapper();
  // mapper.registerModule(new JavaTimeModule());
  // try {
  // Pedido pedido = mapper.readValue(message, Pedido.class);
  // log.info("Pedido recebido: {}", pedido);
  // // Enviar o pedido para o WebSocket
  // template.convertAndSend("/topic/pedidos", pedido);
  // } catch (Exception e) {
  // log.error("Erro ao processar a mensagem: {}", message, e);
  // }
  // }

}
