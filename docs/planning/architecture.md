# Arquitetura do planejamento — ARCH-HUB-01

Responsável: agente Software Architect da EquipeTecnica. Nível 2, por envolver contratos entre interface, API e persistência. Data: 28/10/2026. Escopo: planejamento; nenhuma mudança de comportamento foi implementada, compilada ou testada.

## Evidências e comportamento atual

Não foram encontrados `AGENTS.md` nos ancestrais consultados nem no repositório. O comando `/caveman-lite` não está disponível nesta sessão; não foi executado.

| Área | Evidência no código | Consequência para o planejamento |
| --- | --- | --- |
| Tasks locais | `front/src/services/taskHub.js` → `TaskController` → `TaskService` → `TaskRepository` → `Task`/`hub_task` | Estender os responsáveis existentes. O estado Vue só muda após sucesso da API. |
| Contratos | GET lista todas; POST cria UUID; PATCH recebe data, horário, conclusão e atributos; DELETE remove | PATCH não é um patch esparso completo: `completed` é boolean primitivo, datas nulas limpam campos. Novas operações não podem reaproveitá-lo passando somente um atributo. Título não é editável no contrato atual. |
| Datas | `LocalDate`/`LocalTime` na entidade; calendário usa chaves locais | Preservar data civil; não converter automaticamente datas em instantes UTC. |
| Notificações | `utils/notifications.js` seleciona pendentes com data até amanhã; `NotificationsView` filtra e pesquisa título/categoria/notas | Há busca restrita às notificações, não uma busca global. Não há evidência de entrega de lembrete do sistema para tasks locais. |
| Preferências | `utils/preferences.js` e `calendarPreferences.js`, armazenadas em localStorage | Preferências são distintas das tasks persistidas e ficam fora da cópia portátil de tarefas deste MVP. |
| Dados | `application.properties`: H2 em arquivo, `ddl-auto=none`, `sql.init.mode=always`; `schema.sql` com criação/alterações idempotentes | Não presumir migrações versionadas em execução: existe arquivo em `db/migration`, mas o POM consultado não declara Flyway/Liquibase. |
| Limite local | Backend em 127.0.0.1; Compose publica portas em loopback; `/api/tasks/**` liberado na configuração de segurança | Nenhum plano autoriza exposição pública ou multiusuário. Novos endpoints precisam de revisão explícita da configuração de segurança. |
| Google | Controllers/services OAuth e sincronização existem; frontend taskHub usa somente tasks locais; chave OAuth depende de Windows DPAPI, conforme README | Integração pausada é fato do produto, não ausência de código. Não incluir credenciais ou tabelas Google no backup portátil de tasks. |
| Downloads | `VideoDownloadController`/`VideoDownloadService` já executam yt-dlp; frontend `VideoDownloaderView` apenas valida e abre link externo | README descreve corretamente a experiência sem download, mas a infraestrutura parcial existe. Não planejar um serviço do zero nem declarar download funcional. Segurança atual autentica rotas fora de `/api/tasks/**`. |

## Fronteiras e invariantes

1. A fonte canônica das tasks continua sendo a API/H2; filtros e mockups não se tornam uma segunda base de dados.
2. Sucesso visual de escrita depende do sucesso da API. Erro preserva entrada do usuário e permite recuperação.
3. Tasks continuam locais, com UUID e dados civis opcionais. Concluir uma tarefa não altera tarefas independentes.
4. Dados Google e segredos ficam fora de exportações; nenhuma nova dependência de serviço externo é necessária.
5. Backup não substitui silenciosamente a base atual. Prévia e confirmação devem refletir exatamente os dados que serão aplicados.
6. Mudanças de schema devem ser aditivas e compatíveis com dados atuais, com backup verificado e plano de retorno.

## Revisão das quatro features

Revisão alinhada ao handoff final `PO-HUB-01`, em `po-features.md`. O desenho abaixo registra a direção técnica das quatro features, sem autorizar implementação antecipada.

### F01 — Busca global de tarefas

- Dono: interface Vue, utilizando a coleção carregada por `useTaskHub`; API atual basta para o volume local sem evidência de gargalo.
- Consolidar a normalização textual hoje contida em `NotificationsView` em utilitário compartilhado quando necessário. Escopo: texto e filtros definidos pelo PO, sempre sobre todas as tasks carregadas, incluindo sem data e concluídas conforme filtro explícito.
- Navegar ao detalhe pela identidade da task; preservar consulta/filtros ao voltar. Sem motor externo, índice persistente ou nova tabela.
- Combinar texto (título/categoria/notas com OU) e filtros (com E); intervalo inclusivo e somente sem data são mutuamente exclusivos. Aplicar ordenação determinística do PO: pendência, data, horário, título, ID. Consulta vazia cobre todo o acervo.
- Tratar carregamento, falha de leitura e resultado vazio separadamente. Lista vazia após falha de backend não significa ausência de tarefas.

### F02 — Backup portátil e importação segura

- Dono: aplicação local; exportação/importação canônica de tasks na API. Preferências do navegador ficam fora do MVP; nome do produto na interface: “Cópia das tarefas”, sem prometer backup completo do Hub.
- Formato JSON versionado, validado integralmente no servidor: tipos, UUID, limites de texto, enum, cor, data/hora, quantidade, bytes e unicidade. Exportar DTO permitido, nunca tabelas brutas nem credenciais Google. Limites iniciais propostos com DBA: arquivo de até 5 MiB (5.242.880 bytes) e até 5.000 tasks; são proteções de entrada do MVP, não volume suportado medido. Validar antes de materializar/gravar o conjunto inteiro e informar limites na interface.
- Prévia sem escrita, vinculada ao mesmo conteúdo confirmado. Revalidar no momento de aplicar e executar toda importação de tasks em transação. Mesmo ID e conteúdo idêntico é ignorado; mesmo ID e conteúdo diferente bloqueia o arquivo inteiro, inclusive itens novos. Aplicar a mesma regra a duplicidades internas inconsistentes.
- Se alterações concorrentes mudarem o resumo, exigir nova prévia antes de confirmar. Serializar a comparação e a escrita contra as operações locais concorrentes relevantes; uma prévia no cliente não fornece essa garantia. Nenhum campo é mesclado ou substituído no MVP.
- Segurança das novas rotas e aplicação dos limites no transporte/parser precisam ser fechadas antes da implementação. Se o acervo superar o limite de importação, não permitir exportação que aparente ser recuperável pelo MVP nem truncar silenciosamente: mostrar limitação e orientar cópia operacional consistente do H2. A cópia JSON portátil não substitui cópia consistente do arquivo H2 com backend parado.

### F03 — Recorrência semanal

- Dono: `TaskService` para geração validada e transacional; formulário Vue fornece início obrigatório, horário opcional e quantidade inteira de 2 a 52 ocorrências semanais.
- Materializar ocorrências como tasks independentes com datas civis. Não adicionar scheduler ou geração infinita. A prévia deve usar as mesmas regras de datas da gravação.
- Impedir dupla geração por repetição/retry da mesma submissão com chave persistente de operação e unicidade. Editar/concluir/excluir uma ocorrência não altera as demais neste MVP.
- Manter identidade e estado persistentes da série: ocorrência independente continua vinculada ao histórico. Encerrar série é comando explícito e transacional: remove somente pendentes com data civil estritamente posterior a hoje; preserva hoje, passadas e todas as concluídas. A contagem deve ser revalidada contra mudanças concorrentes; repetição do encerramento já aplicado é inócua. Não derivar “encerrada” somente da ausência de futuras tarefas.
- A decisão de “hoje” precisa ter uma única fonte para prévia/confirmação (data civil/fuso da instalação, sem assumir timezone do host Docker igual ao navegador). Registrar o dia considerado no resumo e revalidá-lo no servidor na confirmação. Mudança de dia exige resumo atualizado.
- O formato de cópia deve preservar série, estado e vínculo histórico, sem confundir ocorrência com regra geradora ativa. DBA revisa identidade/idempotência e retenção; não omitir esses dados na exportação.
- Não oferecer checklist nas tarefas vinculadas a série nesta primeira entrega; mensagem explícita e validação também no servidor.

### F04 — Checklist dentro da tarefa

- Dono: agregado Task; até 20 itens com identidade, ordem de criação, texto não vazio de até 200 caracteres e conclusão próprios. API salva alterações atomicamente no agregado e não reutiliza PATCH parcial ignorando campos existentes. Texto repetido é permitido, identidade repetida não.
- Conclusão dos itens não conclui automaticamente a task. Concluir task com itens pendentes exige confirmação explícita e não marca os itens. Tarefa concluída mantém checklist em leitura; só após reabrir pode editar, preservando marcas. Servidor aplica as mesmas regras; confirmação precisa corresponder à revisão atual para não ignorar itens adicionados por outra aba.
- Remover item exige confirmação com o texto; não há reordenação manual, subtarefas recursivas, dependências ou sistema de projetos. Não compor checklist com séries sem novo escopo PO.
- DBA define armazenamento e integridade. Exclusão da task deve remover seus itens de forma transacional. Exportação/importação precisa incluir checklist e validar relação/ordem.
- Definir proteção contra atualizações concorrentes antes de novas operações de edição, evitando uma aba sobrescrever alterações feitas por outra.

## Sequência técnica proposta

1. Fechar critérios PO, fluxo Designer e política de conflitos com DBA; registrar contratos e limites.
2. Entregar busca global reutilizando leitura atual, como primeira fatia sem mudança de schema.
3. Entregar backup/importação para o modelo atual, com arquivo de exemplo e cenários negativos. Provar recuperação antes de ampliar dados persistidos.
4. Entregar recorrência finita; manter compatibilidade do backup e provar idempotência/independência.
5. Entregar checklist; ampliar schema e formato de backup de maneira versionada, aceitando arquivos antigos com checklist vazio.

É possível inverter recorrência e checklist por prioridade do PO após avaliação de esforço. Não existe dependência funcional entre as duas; ambas dependem de preservação dos dados atuais e da evolução coerente do backup.

## Riscos e mitigação

- **Sem volume medido:** a lista integral já existe. Não acrescentar índices ou paginação por hipótese; medir antes de alterar o contrato de leitura.
- **Concorrência:** atualizações atuais podem sobrescrever alterações de outra aba. Para operações novas que possam perder dados, definir revisão/controle otimista e retorno de conflito com recarga, em vez de implementar retries cegos.
- **Importação:** limites e validação devem ocorrer antes da escrita. Alteração concorrente entre prévia e aplicação exige nova comparação no servidor.
- **Arquivos antigos:** formato versionado com defaults explícitos; versão futura desconhecida é rejeitada sem escrita. Schema evolui por expansão, sem limpeza automática de dados.
- **Tempo civil:** recorrência em dias de calendário, não soma de intervalos UTC. Validar ano bissexto, mudança de mês/ano e ausência de horário.
- **Acessibilidade:** estados de erro e progresso anunciados, navegação por teclado e foco definidos pelo Designer; gráficos de checklist nunca dependem apenas de cor.

## Estratégia de validação para Tester

Proposta, não execução. Combinar matriz PO-HUB-01 com os seguintes pontos:

- Busca: acentos/maiúsculas, tarefa sem data, concluída, combinações de filtros, navegação e erro de backend.
- Importação: arquivo válido/antigo, versão futura, payload malformado, IDs duplicados, enum inválido, excesso de tamanho, conflito após prévia, retry e rollback de falha no meio do lote. Confirmar zero alteração em rejeições.
- Recorrência: primeira/última ocorrência, fronteira mês/ano, limites 2/52, datas inválidas, envio duplicado e independência ao editar/concluir/excluir uma ocorrência. Encerrar preserva hoje/passadas/concluídas; retry é inócuo, mudança concorrente/dia invalida resumo e backup preserva identidade/estado histórico.
- Checklist: ordem e identidade preservadas, limites 20/200, múltiplas abas, exclusão da task e round-trip por cópia. Verificar conclusão com pendências confirmada, leitura após concluir, reabertura mantendo marcas e rejeição da combinação com série.
- Regressão: calendário dia/semana/mês/ano, notificações e preferências atuais. Build/testes automatizados e browser QA pertencem ao Tester, após implementação aprovada.

## Rollout, rollback e decisão

- **Rollout:** uma feature por incremento; registrar schema/formato de arquivo e reter compatibilidade. Backup consistente antes de alterar persistência. Não ativar Google/downloads por efeito colateral.
- **Rollback:** voltar código somente se schema aditivo permanecer compatível e operações antigas preservarem estruturas novas, incluindo integridade de filhos em exclusões; conservar dados/colunas novas. Se houve corrupção ou mudança incompatível, parar escrita, preservar cópia do estado atual e avaliar perda de gravações posteriores antes de restaurar backup consistente sob [DATA-HUB-01](data-plan.md); não apagar volume nem executar down --volumes.
- **DBA status:** approved with conditions para a direção de planejamento de cópia/importação, recorrência e checklist, conforme [DATA-HUB-01](data-plan.md); not applicable para busca sem persistência. Condições compartilhadas: fechar contratos/revisões e prévia, política de data local, DDL compatível, guardas de entrada e comprovação futura de atomicidade/recuperação pelo Tester. Não é liberação para implementar banco.
- **Decision:** approved with conditions para a direção de planejamento. **Não há aprovação para executar mudanças no aplicativo ou no banco neste pedido.** Contratos, limites, política de conflitos, plano DBA e critérios finais precisam ser fechados antes de um futuro handoff de implementação.
- **Risco não resolvido:** esforço e desempenho são estimativas sem medição de uso; prioridade não substitui teste com usuário. Nenhuma validação de entrega foi executada por este papel.
