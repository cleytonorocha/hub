# Novos módulos para o Hub

Rodada 2 · 28/10/2026. Pedido: explorar novas funcionalidades além das melhorias de tarefas e calendário. Propostas de produto e mockups; nenhuma funcionalidade foi implementada.

O primeiro levantamento permanece em [planejamento do núcleo](../README.md). Esta rodada expande o propósito do Hub para reunir áreas da organização pessoal. As prioridades são hipóteses qualitativas, sem pesquisa ou métricas de adoção.

## Novas oportunidades

| Prioridade proposta | Módulo | Valor para a pessoa | Três telas |
| --- | --- | --- | --- |
| N1 | Notas e conhecimento | Guardar ideias, decisões e referências sem perdê-las entre tarefas | Biblioteca de notas → editor → nota com links e tarefa associada |
| N2 | Finanças pessoais manuais | Entender entradas e saídas do mês e revisar gastos | Resumo mensal → lançar receita/despesa → extrato e revisão |
| N3 | Hábitos e rotinas | Registrar consistência de práticas pessoais sem recadastrar tarefas | Painel de hoje → configurar hábito → histórico semanal |
| N4 | Sessões de foco | Reservar tempo de atenção e acompanhar o que foi feito | Preparar sessão → sessão em andamento → resumo e histórico |
| N5 | Biblioteca de leitura | Reunir conteúdos a consumir e registrar aprendizado | Fila de conteúdos → cadastrar conteúdo → progresso e nota |
| N6 | Documentos e vencimentos | Localizar documentos e acompanhar validade/renovação | Catálogo → registrar documento → detalhe e renovação |

N1 → N2 → N3 é a shortlist recomendada pelo PO. As demais são alternativas completas de expansão, não compromissos de implementação. A escolha deve considerar utilidade real e sensibilidade dos dados, além de esforço técnico.

## Entregáveis da Equipe Técnica

- **PO-HUB-02:** [levantamento, prioridades, recortes e critérios](../po-new-modules.md).
- **UX-HUB-02:** [especificação do Designer](design.md), [galeria das 18 telas](screens/index.html) e [prancha geral](screens/overview.svg).
- **ARCH-HUB-02:** [viabilidade, fronteiras e sequência](../architecture-new-modules.md).
- **DATA-HUB-02:** [dados, integridade, privacidade e recuperação](../data-new-modules.md).

## Passo a passo de planejamento

1. Comparar as seis oportunidades e identificar os problemas que você realmente enfrenta no dia a dia.
2. Usar os três momentos desenhados por módulo para discutir o fluxo completo, não apenas sua tela inicial.
3. Escolher um módulo e fechar seu MVP, critérios, estados de erro e exclusões com o PO.
4. Revisar os contratos e o tratamento dos dados com Arquiteto e DBA antes de alterar o aplicativo.
5. Entregar um módulo por vez por meio do Executor; o Tester verifica os cenários aprovados após a implementação.
6. Revisar a prioridade das demais propostas com o que foi aprendido no uso.

## Decisões, invariantes e limites

- Conteúdo das telas e métricas exibidas são fictícios. Os controles desenhados não gravam dados nem executam integrações.
- N2 trata registros manuais em BRL; não promete conexão bancária, pagamentos ou aconselhamento financeiro.
- N6 é organização de documentos; o nome não implica cofre, criptografia ou armazenamento seguro já implementado.
- N4 não promete alertas com aplicativo fechado nem atividade real em segundo plano.
- N5 não promete download, captura automática de páginas ou leitura do conteúdo remoto.
- Nenhum novo módulo depende de sincronização Google, IA ou serviço externo para justificar seu primeiro recorte.
- Preservar tarefas, datas civis e dados atuais é condição de qualquer implementação futura. O modelo existente não deve limitar a descoberta nem ser usado artificialmente para armazenar todo tipo de registro.
- Executor e Tester não foram ativados nesta rodada de planejamento. Não houve build, testes ou QA de navegador. Os registros técnicos são avaliações da direção proposta, sem alegar funcionalidades prontas.
- Caveman Lite não estava disponível e não foi executado.
