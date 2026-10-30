# Planejamento de novas features do Hub

Data: 28/10/2026. Escopo: descoberta, priorização, desenho de experiência e análise técnica. Os arquivos desta pasta são propostas; não representam funcionalidades implementadas.

**Rodada 2:** o [levantamento de novos módulos](new-modules/README.md) amplia o Hub além das tarefas e do calendário, com seis propostas novas e três telas por módulo. Esta primeira rodada permanece como melhoria do núcleo existente.

## Equipe e entregáveis

| Papel | Registro | Entrega |
| --- | --- | --- |
| Product Owner | PO-HUB-01 | [Features priorizadas e critérios de aceite](po-features.md) |
| Designer | UX-HUB-01 | [Fluxos e especificação das telas](design.md), [galeria de 12 telas](screens/index.html) |
| Arquiteto | ARCH-HUB-01 | [Responsabilidades, contratos e sequência técnica](architecture.md) |
| DBA | DATA-HUB-01 | [Persistência, migração e recuperação](data-plan.md) |

Executor e Tester não foram ativados: o pedido é de planejamento e desenho de telas. As matrizes de cenários são propostas para execução futura pelo Tester. Não houve build, testes automatizados ou validação de navegador nesta etapa.

## Passo a passo

1. **Levantar a situação atual.** Ler o README e rastrear o comportamento no código. Distinguir tarefas locais, preferências do navegador, integração Google pausada e download com frontend ainda incompleto. Registrar divergências da documentação como evidência, sem assumir que uma classe existente significa fluxo pronto.
2. **Definir o resultado para a pessoa usuária.** O PO relaciona cada proposta à organização pessoal e ao calendário, descreve o problema e separa evidência de hipótese. As prioridades deste levantamento são qualitativas; não há entrevistas ou métricas de uso que as comprovem.
3. **Priorizar um recorte completo.** Ordenar valor, dependências, risco e esforço. O PO estabelece o que entra e fica fora, as regras e os critérios de aceite. Cada feature deve poder ser entregue e avaliada separadamente.
4. **Desenhar três telas por feature.** O Designer transforma cada recorte em três etapas conectadas, preserva a identidade visual do Hub e especifica estados vazios, erros, recuperação, versão móvel e acessibilidade. As três telas são etapas do fluxo, não três alternativas visuais.
5. **Revisar viabilidade.** O Arquiteto identifica os componentes responsáveis, contratos e invariantes. O DBA avalia persistência, compatibilidade, transações, importação, backup e recuperação. Divergências devem voltar ao PO ou Designer antes de programar.
6. **Revisar o pacote de proposta.** Conferir se escopo, telas, regras e riscos contam a mesma história. Aprovação de planejamento não é prova de implementação nem de funcionamento. As condições técnicas registradas devem ser resolvidas antes da alteração correspondente.
7. **Preparar a primeira entrega.** Escolher a feature prioritária e fechar seus contratos e cenários. Com as aprovações técnicas aplicáveis, o Executor implementa o recorte. Não antecipar as quatro features em uma mudança única.
8. **Validar e decidir a próxima entrega.** O Tester executa os cenários do PO e os requisitos de UX e dados. Falhas retornam ao Arquiteto/DBA para plano de correção; o Executor corrige e o Tester verifica novamente. Somente então atualizar o README e avançar para a próxima feature.

## Ordem proposta pelo PO

| Ordem | Feature | Três telas de planejamento |
| --- | --- | --- |
| F1 | Busca global de tarefas | Central de busca → filtros e resultados → detalhe com retorno preservado |
| F2 | Cópia portátil e importação segura de tarefas | Exportar cópia → selecionar e validar arquivo → revisar e confirmar importação |
| F3 | Tarefas recorrentes | Configurar repetição → revisar próximas ocorrências → gerenciar série |
| F4 | Checklist dentro da tarefa | Editar checklist → executar checklist → revisar conclusão da tarefa |

A busca parte de dados já carregados. Backup protege os dados locais antes de adicionar estruturas mais ricas. Recorrência e checklist introduzem novas regras de persistência e precisam de revisão específica. Consulte os registros dos papéis para as condições e os limites exatos.

## Registro de decisões e limites

- Manter o foco de produto em tarefas pessoais e calendário; integrações Google, notificações do sistema e downloads não fazem parte destes quatro recortes.
- Reutilizar a aplicação Vue/Spring Boot/H2 existente. Evitar serviços novos sem necessidade demonstrada.
- **Divergência resolvida:** a proposta técnica inicial incluía preferências na cópia. O PO restringiu F2 às tarefas; arquitetura, dados e telas devem seguir esse recorte. Preferências do navegador e credenciais ficam fora.
- **Regras do recorte:** na importação, mesmo ID com conteúdo diferente bloqueia o arquivo inteiro. Séries semanais têm de 2 a 52 ocorrências. Checklist tem até 20 itens de 200 caracteres e não é combinado automaticamente com recorrência.
- Dados de exemplo das telas são fictícios. Os mockups são estáticos e não gravam tarefas nem importam arquivos.
- Não substituir ou corrigir o README principal nesta entrega: as divergências encontradas estão no levantamento técnico.
- Validação de usuário, estimativas de prazo e eficácia comercial permanecem em aberto. Os documentos registram hipóteses e estratégia de verificação, sem resultados inventados.
- O comando Caveman Lite não estava disponível entre as ferramentas desta sessão; não foi executado.

## Fontes

[README do projeto](../../README.md), fontes em `front/src` e `back/src`, esquema SQL e configurações de persistência, além das capturas existentes em `docs/screenshots`. Evidências específicas estão vinculadas nos registros de cada papel.
