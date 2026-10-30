# Planejamento de produto — Hub

Handoff: **PO-HUB-01** · Papel: Product Owner · Nível 2 · Data: 28/10/2026.

Decisão: recomendar quatro incrementos para revisão de produto e desenho de três telas conectadas por incremento. Este documento aprova o escopo para planejamento; não autoriza execução nem declara implementação validada. Arquiteto e DBA devem revisar os impactos antes de implementar. `/caveman-lite` não foi executado: não há comando correspondente disponível nesta atuação.

## Evidências, fatos e hipóteses

Fontes consultadas: `README.md`, `front/src/views/CalendarView.vue`, `front/src/views/OverviewView.vue`, `front/src/views/NotificationsView.vue`, `front/src/components/TaskCalendarList.vue`, `front/src/components/TaskComposerModal.vue`, `front/src/services/taskHub.js`, `front/src/utils/preferences.js`, `back/src/main/java/com/github/cleytonorocha/hub/entity/Task.java`, `back/src/main/java/com/github/cleytonorocha/hub/dto/CreateTaskRequest.java`, `back/src/main/java/com/github/cleytonorocha/hub/service/TaskService.java` e `back/src/main/resources/application.properties`.

**Fatos observados no código e documentação:**

- O produto organiza tarefas pessoais locais por data, horário, categoria, prioridade, cor, notas e conclusão; admite tarefas sem data.
- O calendário oferece dia, semana, mês e ano, criação contextual, detalhes e alteração de propriedades da tarefa.
- Notificações já oferece busca sem distinção de caixa/acentos em título, categoria e notas, além de filtros de vencidas, hoje e amanhã. Isso não é busca global: a base é a coleção de notificações pendentes.
- O calendário recebe todas as tarefas, mas `filteredTasks` não aplica busca. `TaskCalendarList` apresenta tarefas; não possui mecanismo de busca próprio.
- A tarefa local não possui repetição nem checklist no modelo consultado.
- O backup documentado depende de parar o backend e copiar o banco H2. Preferências são armazenadas separadamente no navegador.
- Integração Google está pausada no README. Downloads têm informações conflitantes no próprio README e devem ser diagnosticados antes de qualquer promessa de capacidade.

**Hipóteses de produto, ainda sem pesquisa com usuários ou métricas:** localizar tarefas antigas/futuras fica mais difícil conforme o histórico cresce; exportação portátil reduz insegurança quanto aos dados; rotinas repetidas causam recadastro; tarefas maiores precisam de etapas. Impacto, esforço e confiança abaixo são estimativas qualitativas, não medições.

Ator comum: pessoa que usa sua própria instalação local do Hub. Não se presume conta autenticada, equipe, múltiplos donos ou acesso remoto. Conteúdo de tarefas pode ser pessoal; não enviar notas ou arquivos a serviços externos nesta proposta.

## Priorização e sequência

| Ordem | Feature | Impacto esperado | Esforço relativo estimado | Confiança | Motivo e dependência |
| --- | --- | --- | --- | --- | --- |
| 1 / P1 | F1 — Busca global de tarefas | Alto na recuperação de informação | Baixo a médio | Alta na lacuna funcional; média no valor percebido | Aproveita campos e padrão de busca existentes; inclui futuro, sem data e concluídas. |
| 2 / P1 | F2 — Cópia portátil e importação segura de tarefas | Alto para continuidade e proteção dos registros | Médio | Alta na limitação atual; média na frequência de uso | Oferece recuperação utilizável antes de aumentar a complexidade do modelo. |
| 3 / P2 | F3 — Tarefas recorrentes semanais | Alto para rotinas | Médio a alto | Média | Evita recadastro; depende de definição de ocorrência e compatibilidade de cópia/importação. |
| 4 / P2 | F4 — Checklist dentro da tarefa | Médio para execução de tarefas maiores | Médio | Média | Acrescenta etapas sem criar gestão de projetos; depende de preservar checklist em cópias. |

P1/P2 representam ordem proposta do backlog, não severidade de defeito ou compromisso de prazo. Cada feature deve ser entregue separadamente. F3 e F4 exigem que F2 acompanhe os novos dados antes de a nova versão ser liberada, evitando cópias incompletas silenciosas.

Ficam para um levantamento posterior: colaboração, IA no produto, projetos complexos, cobrança, sincronização Google, notificações de sistema em segundo plano e expansão do downloader. O foco desta rodada é consolidar a organização pessoal local. Editar o título de tarefa existente, hoje ausente do contrato de atualização consultado, deve ser registrado como melhoria corretiva de usabilidade em avaliação separada; não está implícito nos mockups desta rodada.

## F1 — Busca global de tarefas

**Delivery slice:** localizar uma tarefa em todo o acervo local e voltar ao contexto de busca após consultá-la.

**User and outcome:** pessoa que lembra de palavras ou categoria, mas não da data; encontra o registro sem percorrer calendários.

**In scope / out of scope:** busca por título, categoria e notas; filtros combinados de estado, categoria, prioridade e período; incluir tarefas sem data e concluídas; abrir detalhe e data no calendário. Fora: busca remota, pesquisa semântica, filtros salvos, operações em lote e alteração do título.

**Business rules and assumptions:**

1. A entrada global pesquisa inicialmente todas as datas, incluindo sem data e ambos os estados. Consulta vazia mostra o acervo com indicação clara do escopo.
2. Normalização ignora caixa e acentos, como já ocorre em Notificações. Busca textual combina os três campos com OU; texto e grupos de filtros combinam com E.
3. Estado: todas, pendentes ou concluídas. Uma categoria e uma prioridade por vez no MVP. Período: todas as datas, intervalo inclusivo ou somente sem data. Intervalo e somente sem data são mutuamente exclusivos; data inicial posterior à final é erro explícito.
4. Ordenação determinística: pendentes antes de concluídas, depois data crescente; sem data após as datadas; em seguida horário (ausente após horários), título e identificador como desempate.
5. Chips mostram filtros ativos e podem ser removidos. Limpar filtros não apaga registros. A contagem refere-se ao resultado aplicado.
6. Abrir e fechar o detalhe preserva consulta, filtros e posição de leitura. Abrir no calendário só aparece para registro com data. Tarefa sem data permanece acessível no detalhe.
7. Falha de carregamento não deve parecer coleção vazia. Resultados antigos, se mantidos, devem ser marcados como desatualizados; nova tentativa deve ser possível.

**Três telas conectadas para o Designer:**

- **F1-T1 — Central de busca:** entrada “Buscar em todas as tarefas”, escopo geral, contagem, resultados iniciais, estados de carregamento/erro e entrada de filtros.
- **F1-T2 — Resultados filtrados:** consulta “planejamento”, chips “Trabalho” e “Pendente”, painel de filtros e resultado vazio recuperável como estado alternativo.
- **F1-T3 — Detalhe e retorno:** detalhe da tarefa selecionada, data e propriedades já existentes, retorno aos resultados preservados e atalho ao calendário quando houver data.

**Acceptance criteria:** tarefa futura, concluída e sem data devem ser encontráveis por palavra em título/notas/categoria; “reunião” e “REUNIAO” produzem o mesmo conjunto; filtros em conjunto excluem registros incompatíveis; limpar filtros recupera todos os registros; retorno preserva a seleção; erro apresenta ação de tentar novamente.

**Métrica proposta:** taxa de tentativas em que a pessoa encontra a tarefa correta sem navegar manualmente por datas; tempo até abrir o detalhe em uma sessão de usabilidade. Sem baseline ou alvo numérico comprovado; coletar primeiro em sessões consentidas, sem telemetria de conteúdo por padrão.

**Dependências e risco:** manter coerência com Notificações e contexto do calendário. Não prometer latência ou volume suportado sem medição; Arquiteto define a solução e Tester mede com dados representativos. Risco de UX: confundir busca global com filtro apenas do período visível.

**Tester proposal — especificação, não executada:**

| Scenario ID | Priority | Preconditions | Actor/permission | Steps | Expected result | Error/negative state | Browser-visible assertion | Regression scope |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F1-01 | P1 | Tarefas futura, concluída e sem data com termo comum | Usuário local | Buscar termo sem filtros; abrir cada detalhe | Todas as correspondências acessíveis | Nenhum resultado real | Escopo global e contagem corretos; vazio oferece limpar | Notificações e calendário mantêm seus escopos |
| F1-02 | P1 | Tarefas com categorias e prioridades distintas | Usuário local | Aplicar texto, estado, categoria e intervalo; remover chips | Interseção correta, sem alterar dados | Intervalo invertido | Erro junto ao período; filtros ativos visíveis | Estados e datas das tarefas preservados |
| F1-03 | P1 | Consulta “reuniao” e tarefa “Reunião”; API indisponível em etapa separada | Usuário local | Buscar; abrir detalhe; voltar; provocar falha e tentar de novo | Normalização e contexto preservados; erro explícito | Falha de carregamento | Nenhum falso estado vazio | Criação/edição e foco de retorno |

## F2 — Cópia portátil e importação segura de tarefas

**Delivery slice:** exportar e importar as tarefas locais com inspeção antes de qualquer gravação.

**User and outcome:** pessoa que quer manter uma cópia legível pelo Hub ou transferir tarefas entre sua instalação local e uma nova instalação, sem manipular arquivos internos do banco.

**In scope / out of scope:** cópia completa do acervo de tarefas locais, incluindo concluídas e sem data; arquivo reconhecível pelo Hub; importação aditiva com prévia, detecção de duplicidade e conflitos. Fora: substituição integral do acervo, merge de campos, backup automático, nuvem, preferências do navegador, credenciais/OAuth, downloads e cópia física do banco. A interface deve dizer “cópia das tarefas”, não “backup completo do Hub”.

**Business rules and assumptions:**

1. Exportação deve manter todos os campos necessários para reconstruir fielmente cada tarefa suportada, identificadores e versão de formato. Não incluir credenciais nem conteúdo de ferramentas externas.
2. Mostrar total e data/hora da cópia; só anunciar sucesso quando o arquivo estiver pronto para o navegador oferecer o salvamento. A aplicação não deve afirmar que confirmou gravação física no computador.
3. Importar não muda dados antes da confirmação. A prévia apresenta origem/versão, total de itens novos, idênticos e conflitantes. Não executar conteúdo do arquivo nem interpretar notas como HTML.
4. Identificador e conteúdo idênticos: ignorar como duplicado. Mesmo identificador e conteúdo diferente: bloquear o arquivo inteiro no MVP; informar o conflito sem sobrescrever. Não inventar cópia com novo identificador sem consentimento.
5. Versão não suportada, tipo incorreto, campo inválido, arquivo acima do limite aceito ou duplicidades internas inconsistentes: rejeitar antes da gravação. Limite de tamanho será definido por Arquiteto/DBA e informado na seleção.
6. Confirmar grava os itens novos como uma única operação do ponto de vista do usuário: ou todos entram ou nenhum. Confirmar novamente a mesma cópia não duplica tarefas.
7. Se os dados locais mudarem entre prévia e confirmação, revalidar os conflitos e exigir revisão da nova prévia quando o resultado mudar. Falha mantém o acervo anterior; cancelar a prévia não grava nada.
8. Usar “Adicionar tarefas”; não prometer restauração de preferências ou de uma instalação inteira. Recomendar guardar o arquivo em local privado, pois ele contém textos pessoais.

**Três telas conectadas para o Designer:**

- **F2-T1 — Cópia das tarefas:** Configurações > Dados, escopo incluído/excluído, total de tarefas e ação “Exportar cópia”, com feedback de arquivo pronto.
- **F2-T2 — Selecionar e validar arquivo:** seletor de arquivo, formatos/limite, resumo da cópia e erro recuperável quando incompatível; saída para cancelar.
- **F2-T3 — Revisar e confirmar importação:** contagens de novas/idênticas/conflitantes, amostra revisável, ação “Adicionar N tarefas” somente sem bloqueios e estado final com totais reais.

**Acceptance criteria:** exportar/importar numa instalação vazia preserva campos e quantidade; repetir importação não cria duplicatas; arquivo com uma tarefa inválida ou conflitante não adiciona nenhuma; cancelar não muda o acervo; falha na gravação preserva o estado anterior; dados pessoais e limites são claramente informados; preferências e credenciais não aparecem no arquivo.

**Métrica proposta:** proporção de sessões de recuperação concluídas com contagens e conteúdo preservados; tempo para identificar arquivo incompatível e recuperar o fluxo. Sem baseline; objetivo de integridade do critério é zero perda e zero duplicação nos cenários especificados, a ser comprovado por Tester.

**Dependências e risco:** revisão obrigatória de Arquiteto e DBA para formato, operação atômica, conflito concorrente e compatibilidade. A cópia portátil não substitui estratégia operacional de recuperação do banco. F3/F4 precisam ser incluídas na versão de cópia correspondente antes de sua liberação.

**Tester proposal — especificação, não executada:**

| Scenario ID | Priority | Preconditions | Actor/permission | Steps | Expected result | Error/negative state | Browser-visible assertion | Regression scope |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F2-01 | P1 | Acervo com todos os campos, sem data, notas e concluídas; instalação destino vazia | Usuário local com acesso ao arquivo | Exportar; selecionar no destino; revisar; confirmar | Mesmos registros e valores; sem credenciais | Falha ao preparar arquivo | Totais e escopo claros; sucesso só após operação | Calendário, busca e notificações |
| F2-02 | P1 | Cópia já importada e variante conflitante | Usuário local | Reimportar cópia; tentar variante | Idênticos ignorados; conflito bloqueia tudo | Mesmo ID com notas distintas | Contagens e motivo do bloqueio; nenhum novo item parcial | Identidade e conteúdo de tarefas existentes |
| F2-03 | P1 | Arquivos inválidos e acervo local conhecido | Usuário local | Cancelar prévia; importar versão inválida; simular falha; alterar acervo após prévia | Nenhuma mudança parcial; nova revisão quando necessário | Arquivo grande/corrompido, falha ou conflito concorrente | Mensagem específica e tentar novamente sem falsa conclusão | Integridade do acervo e cópias |

## F3 — Tarefas recorrentes semanais

**Delivery slice:** cadastrar uma rotina semanal com quantidade finita de ocorrências, concluir cada ocorrência e encerrar as pendentes futuras sem mexer no histórico.

**User and outcome:** pessoa que realiza o mesmo compromisso semanal; agenda uma sequência sem recriar manualmente cada tarefa.

**In scope / out of scope:** repetição uma vez por semana no mesmo dia/horário, início obrigatório, quantidade de 2 a 52 ocorrências, prévia de datas e vínculo de série; consulta de ocorrências e encerramento das pendentes futuras. Fora: repetição diária/mensal/anual, regra por conclusão, múltiplos dias na mesma semana, ocorrência infinita, feriados, sincronização e edição em massa da série. Limite de 52 é decisão de MVP proposta para revisão de produto, não capacidade medida do sistema.

**Business rules and assumptions:**

1. Data inicial é a primeira ocorrência; semana é intervalo de sete dias de calendário local. Horário é opcional e igual na criação. Cada ocorrência mantém sua própria conclusão.
2. A prévia informa quantidade e última data, permitindo revisar todo o conjunto antes de confirmar. Data ausente, quantidade fora do intervalo ou título vazio bloqueia salvar.
3. O conjunto é finito e não depende de manter a aba aberta para materializar uma ocorrência. A decisão técnica de materialização cabe ao Arquiteto/DBA.
4. Concluir ou reagendar uma ocorrência afeta apenas aquela tarefa. A interface deve explicitar “Somente esta ocorrência”. Não recalcular as demais quando uma data individual muda.
5. Encerrar série mostra a quantidade de ocorrências pendentes com data estritamente posterior ao dia local da confirmação; essas são removidas após confirmação explícita. Ocorrência de hoje, anteriores e concluídas permanecem. Se nenhuma futura pendente existir, informar que não há ocorrências a remover.
6. Guardar o vínculo histórico de série para os registros preservados. Se a série já foi encerrada, nova confirmação não remove nada adicional. Alteração concorrente exige atualizar o resumo antes de confirmar.
7. Não atribuir automaticamente checklist da F4 às séries neste MVP; a combinação requer levantamento próprio para não introduzir cópia/propagação implícita.

**Três telas conectadas para o Designer:**

- **F3-T1 — Configurar repetição:** extensão de Nova Task com opção semanal, início, horário opcional e quantidade; microtexto sobre independência das ocorrências.
- **F3-T2 — Revisar próximas ocorrências:** datas de todas as ocorrências, quantidade, início/fim e ação “Criar N ocorrências”; alternativa de voltar e corrigir sem perder entrada.
- **F3-T3 — Gerenciar série:** lista de ocorrências e estados, acesso ao detalhe individual e confirmação de encerramento com quantidade afetada e histórico preservado.

**Acceptance criteria:** série de quatro ocorrências tem quatro datas separadas por sete dias, mesmo na virada de mês/ano; uma concluída não altera outras; reagendar uma não desloca as demais; encerrar remove somente futuras pendentes conforme regra; reabrir aplicação mantém série e conclusões; cópia/importação preserva vínculos; erro não cria série parcial.

**Métrica proposta:** cadastros manuais evitados por série efetivamente usada e proporção de usuários em sessão de usabilidade que distinguem ocorrência de série. Sem baseline; não presumir retenção ou aumento de produtividade medidos.

**Dependências e risco:** regras de data local, consistência do conjunto e atualização de F2. Arquiteto/DBA revisam atomicidade, identidade e encerramento. Risco principal: apagar mais tarefas do que a pessoa espera; quantidade e recorte temporal devem aparecer antes da confirmação.

**Tester proposal — especificação, não executada:**

| Scenario ID | Priority | Preconditions | Actor/permission | Steps | Expected result | Error/negative state | Browser-visible assertion | Regression scope |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F3-01 | P1 | Início próximo à virada de ano; 4 ocorrências | Usuário local | Configurar; revisar todas as datas; confirmar; reabrir | 4 ocorrências exatas; 7 dias de intervalo | Data vazia/quantidade inválida | Última data e total corretos; erro junto ao campo | Criação comum permanece disponível |
| F3-02 | P1 | Série com várias pendentes | Usuário local | Concluir uma; reagendar outra | Somente registros escolhidos mudam | Falha ao salvar ocorrência | Escopo individual e resultado persistido | Notificações e calendário por ocorrência |
| F3-03 | P1 | Série com passadas, hoje, futuras pendentes e futuras concluídas | Usuário local | Abrir encerramento; cancelar; confirmar; repetir; exportar/importar | Só futuras pendentes removidas; histórico e vínculo preservados | Mudança concorrente/falha ao encerrar | Contagem revisada e sem falsa remoção parcial | F2 e tarefas comuns independentes |

## F4 — Checklist dentro da tarefa

**Delivery slice:** decompor uma tarefa em itens simples, registrar seu andamento e concluir a tarefa conscientemente.

**User and outcome:** pessoa executando uma tarefa com pequenas etapas, como preparar documentos; acompanha o que falta sem criar tarefas independentes para cada item.

**In scope / out of scope:** checklist de até 20 itens com texto de até 200 caracteres por item, inclusão/edição/remoção, ordem de criação, marcação independente e progresso “X de Y”. Fora: subtarefas com data própria, responsáveis, níveis aninhados, dependências, arrastar para ordenar e propagação para séries recorrentes. Limites são propostas de produto para revisão, não restrições existentes.

**Business rules and assumptions:**

1. Checklist é opcional; tarefa sem itens continua funcionando como hoje. Texto não pode ficar vazio após remoção de espaços. Itens com texto igual são permitidos: etapas podem ter o mesmo nome e identidade própria.
2. Incluir item acrescenta ao final. Remover um item marcado exige confirmação com o texto correspondente; cancelar preserva item e estado. Remover item pendente pode usar a mesma confirmação simples para consistência.
3. Marcar todos os itens não conclui automaticamente a tarefa; disponibiliza a ação explícita “Concluir tarefa”. Não é necessário checklist para concluir uma tarefa comum.
4. Se restam itens pendentes, a ação de concluir informa quantos faltam e oferece voltar ou “Concluir mesmo assim”. Confirmar não marca os itens automaticamente.
5. Tarefa concluída mantém checklist e progresso, em modo de leitura. “Reabrir tarefa” permite retomar edição mantendo as marcas anteriores; não apaga itens.
6. Falha ao salvar deve conservar a entrada para nova tentativa e não mostrar marcação/progresso como persistidos. Sem lista vazia artificial se o carregamento falhar.
7. Checklist deve acompanhar tarefa em F2. Não compor silenciosamente com recorrência: suporte à criação de checklist em tarefa vinculada a série fica fora desta primeira entrega e deve ser comunicado na interface.

**Três telas conectadas para o Designer:**

- **F4-T1 — Editar checklist:** detalhe da tarefa com “Adicionar etapa”, edição inline e contagem de itens/limites; entrada vazia e confirmação de remoção como estados.
- **F4-T2 — Executar checklist:** tarefa pendente com 2 de 4 etapas concluídas, checkboxes com rótulos, progresso textual e feedback de salvamento/erro.
- **F4-T3 — Revisar conclusão:** confirmação que destaca etapas restantes e permite voltar ou concluir mesmo assim; estado final conserva progresso e oferece reabrir.

**Acceptance criteria:** criar, marcar, editar e remover itens mantém identidades corretas após reabrir o app; progresso corresponde exatamente aos itens marcados; último item marcado não conclui a tarefa; concluir com pendências exige confirmação; reabrir mantém checklist; erro conserva texto e estado confirmado anterior; exportar/importar preserva itens e marcas.

**Métrica proposta:** proporção de tarefas com checklist efetivamente utilizado em uma sessão de avaliação e compreensão da conclusão independente. Sem baseline/objetivo quantitativo de adoção; salvamentos não podem produzir perda de item nos cenários de integridade.

**Dependências e risco:** revisão de Arquiteto/DBA para vínculo e persistência; compatibilidade de F2. Risco: pessoa interpretar checklist completo como tarefa concluída ou pensar que concluir marcou todas as etapas. Mostrar os dois estados em texto explícito.

**Tester proposal — especificação, não executada:**

| Scenario ID | Priority | Preconditions | Actor/permission | Steps | Expected result | Error/negative state | Browser-visible assertion | Regression scope |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| F4-01 | P1 | Tarefa comum pendente sem checklist | Usuário local | Criar 4 itens; marcar 2; editar um; cancelar e confirmar remoção; reabrir | Itens e progresso exatos; texto preservado | Vazio/21º item/texto >200 | Validação, confirmação e contagem corretas | Tarefas sem checklist |
| F4-02 | P1 | Checklist com itens pendentes e depois todos marcados | Usuário local | Concluir com pendências; voltar; confirmar; reabrir; marcar todos | Confirmação consciente; conclusão e marcas independentes | Cancelar não conclui | Pendente/concluída e “X de Y” claros | Filtros, calendário, notificações |
| F4-03 | P1 | Checklist salvo; simulação de falha; arquivo exportado | Usuário local | Alterar durante falha; tentar de novo; exportar/importar | Sem perda de entrada nem falso sucesso; cópia fiel | Falha de gravação | Feedback de erro junto à ação e estado persistido identificável | F2 e identidade dos itens |

## Estados comuns e requisitos para o Designer

Cada conjunto tem **exatamente três telas principais**, não três alternativas visuais. Estados alternativos devem ser anotados dentro da tela correspondente, sem inflar o inventário.

| Estado | Comportamento de produto |
| --- | --- |
| Vazio inicial | Explicar a ausência de dados e oferecer a ação pertinente, sem dados fictícios apresentados como reais. |
| Vazio por filtro | Distinguir de acervo vazio; manter consulta e oferecer limpeza. |
| Carregando/salvando | Informar atividade e impedir confirmação repetida enquanto o pedido está em curso. |
| Erro de campo/arquivo | Indicar motivo junto ao campo ou arquivo, preservar entrada e instruir correção. |
| Erro de operação | Não anunciar sucesso; manter contexto e oferecer nova tentativa segura. |
| Confirmação sensível | Mostrar exatamente o que muda e a quantidade afetada, com saída de cancelamento. |
| Acesso | Não exibir login ou permissões inventadas. Preservar a proteção local existente e tratar recusas reais da API como erro. |

Designer deve preservar identidade visual do Hub, garantir leitura e ação em mobile, rótulos visíveis, acesso por teclado, foco restaurado ao fechar detalhes/diálogos e estados distinguíveis sem depender apenas de cor. Alvos, contraste e hierarquia devem ser especificados no handoff do Designer; Tester executará a verificação após implementação aprovada.

## Passo a passo recomendado

1. Confirmar os quatro problemas em conversa ou sessões breves com o usuário real; registrar ajustes como hipóteses, sem inventar pesquisa.
2. Revisar ordem, limites e recortes deste PO-HUB-01. Designer prepara as 12 telas conectadas com seus estados alternativos e dados claramente demonstrativos.
3. Arquiteto confronta as propostas com os módulos e contratos existentes; DBA revisa mudanças de dados e cópias. Registrar divergências e decisões antes de código.
4. Para cada feature isolada, fechar critérios e cenários do Tester; dependências não podem ficar implícitas no desenho.
5. Implementação futura: Executor trabalha somente no escopo revisado por Arquiteto e, quando houver dados, DBA. Nenhuma implementação foi solicitada nesta rodada.
6. Tester faz build, testes e validação de navegador apenas depois da implementação aprovada; falhas voltam aos responsáveis técnicos. Matrizes acima ainda são propostas.
7. Atualizar README e orientações de recuperação com o comportamento comprovado. Só então considerar o incremento entregue e medir o resultado com usuário.

## Registro de decisão e pendências

- **Decisão PO:** priorizar busca global e recuperação portátil antes de expandir o modelo com recorrência e checklist.
- **Suposições:** uso pessoal local; volume não medido; interesse nas quatro features ainda precisa de validação humana; sem envio de dados externos.
- **Invariantes:** preservar tarefas comuns, datas locais, conclusões e identidades; não perder registros; não anunciar integrações indisponíveis como prontas.
- **Limites propostos para revisão:** série semanal de 2 a 52 ocorrências; checklist de 20 itens de 200 caracteres; limite de arquivo depende de decisão técnica.
- **Risco aberto:** README não é evidência suficiente de operação de downloads; revisão documental pode ser necessária, mas não é uma quinta feature desta rodada.
- **Estado:** artefato de planejamento pronto para handoff ao Designer/Arquiteto/DBA. Nenhum build, teste, QA de navegador ou validação de implementação executado pelo PO.
