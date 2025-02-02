package br.com.postechfiap.jlapppedido.usecase.pedido;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import br.com.postechfiap.jlapppedido.domain.cliente.mapper.ClienteMapper;
import br.com.postechfiap.jlapppedido.domain.enums.Estado;
import br.com.postechfiap.jlapppedido.domain.enums.StatusPagamento;
import br.com.postechfiap.jlapppedido.domain.pedido.dto.ItemPedidoDTO;
import br.com.postechfiap.jlapppedido.domain.pedido.dto.PedidoAcompanhamentoDTO;
import br.com.postechfiap.jlapppedido.domain.pedido.dto.PedidoDTO;
import br.com.postechfiap.jlapppedido.domain.pedido.dto.StatusPedidoDTO;
import br.com.postechfiap.jlapppedido.domain.pedido.gateway.IPedidoGateway;
import br.com.postechfiap.jlapppedido.domain.pedido.mapper.ItemPedidoMapper;
import br.com.postechfiap.jlapppedido.domain.pedido.mapper.PedidoMapper;
import br.com.postechfiap.jlapppedido.domain.pedido.model.ItemPedido;
import br.com.postechfiap.jlapppedido.domain.pedido.model.Pedido;
import br.com.postechfiap.jlapppedido.domain.produto.mapper.ProdutoMapper;
import br.com.postechfiap.jlapppedido.infra.config.mq.PedidoPublisher;
import br.com.postechfiap.jlapppedido.shared.exception.NotFoundException;
import br.com.postechfiap.jlapppedido.shared.logger.log.Logger;
import br.com.postechfiap.jlapppedido.usecase.cliente.ClienteUseCase;
import br.com.postechfiap.jlapppedido.usecase.produto.ProdutoUseCase;

@Service
public class PedidoUseCase {

  private final IPedidoGateway pedidoGateway;

  private final ClienteUseCase clienteUseCase;

  private final ProdutoUseCase produtoUseCase;

  private final ItemPedidoUseCase itemPedidoUseCase;

  private final PedidoPublisher pedidoPublisher;

  private final Logger log;

  public PedidoUseCase(IPedidoGateway pedidoGateway, ClienteUseCase clienteUseCase,
      ProdutoUseCase produtoUseCase, ItemPedidoUseCase itemPedidoUseCase, Logger log,
      PedidoPublisher pedidoPublisher) {
    this.pedidoGateway = pedidoGateway;
    this.clienteUseCase = clienteUseCase;
    this.produtoUseCase = produtoUseCase;
    this.itemPedidoUseCase = itemPedidoUseCase;
    this.pedidoPublisher = pedidoPublisher;
    this.log = log;
  }

  public PedidoDTO inserir(PedidoDTO pedidoDTO) {

    log.info("Convertendo para o dominio de Pedido!");
    Pedido pedido = PedidoMapper.toDomain(pedidoDTO);

    log.info("Verificando se o cliente se indentificou!");
    if (!pedido.getCliente().getCpf().isBlank()) {
      pedido.setCliente(
          ClienteMapper.toDomain(clienteUseCase.buscarClientePorCpf(pedido.getCliente().getCpf())));
    } else {
      pedido.setCliente(null);
      log.info("Pedido sem identificação do cliente!");
    }

    pedido.setEstado(Estado.RECEBIDO);
    pedido.setStatusPagamento(StatusPagamento.AGUARDANDO);
    pedido.setDataPedido(LocalDateTime.now());

    log.info("Registrando pedido e gerando id do pedido!");
    pedido.setId(pedidoGateway.inserir(pedido).getId());

    log.info("Processando itens do pedido!");
    for (int i = 0; i < pedido.getItens().size(); i++) {
      pedido.getItens().get(i).setProduto(ProdutoMapper.toDomain(
          produtoUseCase.buscarProdutoPorId(pedido.getItens().get(i).getProduto().getId())));
      pedido.getItens().get(i).setPedidoid(pedido.getId());
    }

    log.info("Incluindo itens ao pedido!");
    List<ItemPedidoDTO> itensPedidoSalvos =
        itemPedidoUseCase.inserir(ItemPedidoMapper.toListItemPedidoDTO(pedido.getItens()));

    List<ItemPedido> itensPedido = ItemPedidoMapper.toListDomainFromDTO(itensPedidoSalvos);

    pedido.setItens(itensPedido);

    log.info("Calculando o valor do Pedido!");
    pedido.setValorPedido(calcularValorTotalPedido(pedido.getItens()));

    log.info("Gerando numero de pedido!");
    pedido.setNumeroPedido(gerarNumeroPedido());

    // pedidoDTO.toPedidoDTO(pedidoGateway.inserir(PedidoMapper.toDomain(pedidoDTO)));
    pedido = pedidoGateway.inserir(pedido);
    pedido.setItens(
        ItemPedidoMapper.toListDomainFromDTO(itemPedidoUseCase.buscarItemPedido(pedido.getId())));
    log.info("{} salvo com sucesso!", pedido.toString());


    try {
      pedidoPublisher.send(PedidoMapper.toEventoPedido(pedido));
    } catch (JsonProcessingException e) {
      e.printStackTrace();
    }

    return PedidoMapper.toDTO(pedido);

  }


  public List<PedidoDTO> buscarTodos() {
    List<PedidoDTO> pedidoDTOs =
        pedidoGateway.buscarTodos().stream().map(pedido -> PedidoMapper.toDTO(pedido)).toList();

    for (int i = 0; i < pedidoDTOs.size(); i++) {
      pedidoDTOs.get(i)
          .setItemPedidoDTOs(itemPedidoUseCase.buscarItemPedido(pedidoDTOs.get(i).getId()));
    }

    log.info("Pedidos encontrados! {}", pedidoDTOs);
    return pedidoDTOs;
  }

  public StatusPedidoDTO buscarStatusPagamentoPedido(String numero_pedido) {

    PedidoDTO dto =
        PedidoMapper.toDTO(pedidoGateway.buscarStatusPagamentoPedido(numero_pedido).orElseThrow(
            () -> new NotFoundException("Pedido: " + numero_pedido + " não foi encontrado!")));

    log.info("Pedido encontrado! {}", dto);

    return StatusPedidoDTO.builder().numeroPedido(dto.getNumeroPedido())
        .statusPagamento(dto.getStatusPagamento()).build();
  }

  public PedidoDTO buscaPedidoNumeroPedido(String numeroPedido) {
    PedidoDTO dto =
        PedidoMapper.toDTO(pedidoGateway.buscaPedidoNumeroPedido(numeroPedido).orElseThrow(
            () -> new NotFoundException("Pedido numero " + numeroPedido + " não encontrado!")));
    log.info("Pedido: {} encontrado com sucesso!", numeroPedido);
    return dto;
  }

  public PedidoDTO atualizar(PedidoDTO pedidoDTO, String numeroPedido) {

    this.buscaPedidoNumeroPedido(numeroPedido);

    log.info("Convertendo para o dominio de Pedido!");
    Pedido pedido = PedidoMapper.toDomain(pedidoDTO);

    log.info("Atualizando o pedido de numero: {} !", numeroPedido);
    pedido.setNumeroPedido(numeroPedido);

    PedidoDTO dto = PedidoMapper.toDTO(pedidoGateway.atualizar(pedido));
    log.info("{} alterado com sucesso!", dto.toString());

    return dto;

  }

  public PedidoDTO atualizarStatusPagamento(String numeroPedido, StatusPagamento statusPagamento) {

    PedidoDTO pedidoDTO = this.buscaPedidoNumeroPedido(numeroPedido);

    log.info("Convertendo para o dominio de Pedido!");
    Pedido pedido = PedidoMapper.toDomain(pedidoDTO);

    log.info("Atualizando o pedido de numero: {} !", numeroPedido);
    pedido.setNumeroPedido(numeroPedido);
    pedido.setStatusPagamento(statusPagamento);

    PedidoDTO dto = PedidoMapper.toDTO(pedidoGateway.atualizar(pedido));
    log.info("{} alterado com sucesso!", dto.toString());

    return dto;

  }

  public List<PedidoAcompanhamentoDTO> buscarPedidosAcompanhamento() {

    List<PedidoDTO> pedidoDTOs =
        pedidoGateway.buscarTodos().stream().map(pedido -> PedidoMapper.toDTO(pedido)).toList();

    List<PedidoAcompanhamentoDTO> lista = pedidoDTOs.stream()
        .map(pedido -> new PedidoAcompanhamentoDTO().toPedidoAcompanhamento(pedido))
        .collect((Collectors.toList()));
    log.info("Convertendo para o dominio de Acompanhamento de pedido!");

    lista.removeIf(t -> t.getEstado().estaFinalizado());
    log.info("Removendo os pedido em Estdo: Finalizado");

    lista.sort(Comparator.comparing(PedidoAcompanhamentoDTO::getEstado).reversed()
        .thenComparing(t -> t.getDataPedido()));
    log.info(
        "Ordenando pedidos na seguinte ordem [Pronto > Em Preparação > Recebido] sendo pedidos mais antigos primeiro que os mais novos");

    return lista;
  }

  @Scheduled(fixedDelay = 30000)
  public void processarPedidosPagos() {
    List<PedidoDTO> pedidoDTOs = pedidoGateway.buscarTodos().stream().map(PedidoMapper::toDTO)
        .filter(pedido -> pedido.getStatusPagamento() == StatusPagamento.APROVADO)
        .filter(pedido -> pedido.getEstado() == Estado.RECEBIDO)
        .filter(pedido -> pedido.isEnviadoCozinha() == false)
        .peek(
            pedido -> pedido.setItemPedidoDTOs(itemPedidoUseCase.buscarItemPedido(pedido.getId())))
        .collect(Collectors.toList());

    PedidoMapper.toEventoPedidoCozinha(pedidoDTOs).forEach(eventoPedidoCozinha -> {
      try {
        pedidoPublisher.sendPedidoCozinha(eventoPedidoCozinha);
        log.info("Pedido: {} enviado para cozinha!", eventoPedidoCozinha.getNumeroPedido());
        pedidoGateway.atualizarEnviadoCozinha(eventoPedidoCozinha.getId(), true);
        log.info("Pedido: {} atualizado para enviado para cozinha!",
            eventoPedidoCozinha.getNumeroPedido());
      } catch (JsonProcessingException e) {
        e.printStackTrace();
      }
    });



  }

  private BigDecimal calcularValorTotalPedido(List<ItemPedido> itemPedidos) {
    BigDecimal valorPedido = BigDecimal.ZERO;

    for (ItemPedido itemPedido : itemPedidos) {
      valorPedido = valorPedido.add(
          itemPedido.getProduto().getPreco().multiply(new BigDecimal(itemPedido.getQuantidade())));
    }

    log.info("Valor do Pedido: R$ {}", valorPedido);
    return valorPedido;
  }

  private String gerarNumeroPedido() {
    String numeroPedido = RandomStringUtils.randomAlphanumeric(6).toUpperCase();
    log.info("Numero de Pedido: {}", numeroPedido);

    if (!pedidoGateway.buscarStatusPagamentoPedido(numeroPedido).isEmpty()) {
      log.info("Numero de Pedido: {} já existe gerando novo numero", numeroPedido);
      gerarNumeroPedido();
    } else {
      log.info("Numero de Pedido: {} disponivel para ser utilizado!", numeroPedido);
    }

    return numeroPedido;
  }



}
