# Explorações de UX — Tasks

As quatro imagens são estudos conceituais, não telas aprovadas. A faixa superior marca explicitamente o conceito. “Pessoal” e “Projeto Aurora · Quadro Produto” são exemplos genéricos; nenhuma fonte foi escolhida.

## Fluxo proposto

1. A partir de uma data no calendário, abrir “Nova task pessoal”.
2. No MVP, exigir título e data. Mostrar validação junto ao campo, e preservar título/data se o salvamento falhar, com ação de tentar novamente.
3. Após salvar, mostrar a Task na data escolhida; abrir detalhe e permitir concluir apenas Tasks pessoais.
4. Tasks Kanban aparecem somente conforme acesso do usuário, com origem e estado legíveis. O detalhe conceitual de quadro é somente leitura. Falha ao buscar dados deve indicar o quadro e permitir retry, sem sugerir escrita ou sincronização automática.
5. Atualizações e lembretes aparecem na central interna de Notificações. Um exemplo mostra prazo amanhã; canal e antecedência real não foram escolhidos.

## Layout, responsividade e acessibilidade

- Desktop: sidebar atual; modal de criação centrado; detalhe em painel lateral; semana por colunas com Tasks associadas à data (sem horário); notificações em lista.
- Mobile: criação como página ou sheet de tela cheia com foco inicial no título; detalhe em painel de tela cheia; semana rolável horizontalmente sem comprimir os sete dias; notificações em coluna única.
- Incluir estados vazio/com Tasks, carregamento, erro de validação e salvamento, quadro desconectado/falha ao carregar. Associar erros ao campo/serviço e oferecer recuperação; não depender apenas de cor.
- Campos com rótulos persistentes e erro via `aria-describedby`; foco inicial/restaurado; modal com nome acessível, Escape, foco contido e ordem previsível.
- Origem e estado sempre em texto além de ícone/cor; carregamento/salvamento anunciados como status e erro bloqueante anunciado; datas com nome completo em pt-BR; alvos confortáveis, foco visível, contraste, teclado e movimento reduzido.

## Em aberto

- Tasks sem data: não aparecem em grades datadas neste conceito; decidir se haverá lista “Sem data”.
- Descrição, prioridade, horário e recorrência foram omitidos; qualquer horário visível na régua/calendário é contexto visual e não atributo da Task.
- Fonte Kanban real e sincronização de leitura/escrita continuam sem decisão. O mockup usa exemplo genérico; a apresentação como somente leitura evita assumir escrita no quadro.
- Resolução de conflitos não está definida. Em erro, informar a falha e oferecer retry, sem sobrescrever silenciosamente.
- Antecedência e canal de lembretes não estão definidos. “Prazo amanhã” é apenas exemplo de notificação na central do app; não implica push ou e-mail.
