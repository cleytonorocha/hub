# Plano de dados — DATA-HUB-01

Papel: Database Administrator da EquipeTecnica. Nível 2. Data: 28/10/2026. Handoff de planejamento alinhado a [PO-HUB-01](po-features.md) e [ARCH-HUB-01](architecture.md). Nenhuma alteração de banco, build, teste ou validação foi executada. Caveman Lite não estava disponível e não foi executado.

## Decision ID / Data impact and level

**DATA-HUB-01 — approved with conditions para a direção de planejamento.** Busca não exige persistência nova; cópia/importação exige contrato de dados e transação; recorrência e checklist alteram o modelo. Aprovação não libera implementação: contratos exatos, migração, compatibilidade e estratégia de concorrência precisam de revisão antes do handoff ao Executor.

## Evidence and growth assumptions

- `Task` mapeia `hub_task` com UUID textual de 36 caracteres, título até 1.024, categoria até 64, cor de 7 caracteres, prioridade até 16 e notas até 2.000. Data e horário são opcionais; conclusão é independente da data. Prioridades são `LOW`, `NORMAL`, `HIGH`; tipos `LOCAL`, `GOOGLE`.
- `TaskService` lista todas as tarefas por `findAll()` e grava operações individuais. Não há `@Version` nem transação de serviço envolvendo lote no código consultado. Não presumir que uma futura importação já seria atômica.
- H2 em arquivo: caminho local configurado em `application.properties`; Docker usa `/data/hub` no volume `hub_data`. São bases independentes. Preferências ficam no localStorage e **não integram F2**, conforme decisão PO.
- `ddl-auto=none`, `sql.init.mode=always` e `schema.sql` são o mecanismo observado de criação/expansão. Um arquivo em `db/migration` não comprova Flyway ativo. Não prescrever migração automática por ferramenta ausente.
- Tabelas Google e credenciais são separadas e ficam fora do formato portátil. Nenhum dado ou segredo é enviado a serviço externo.
- Não há número medido de tarefas, taxa de crescimento, tamanho do banco, latência, RPO/RTO ou distribuição de acesso. Não recomendar particionamento, replicação, cache ou mudança de banco sem evidência.

## Alternatives considered

| Tema | Alternativa | Decisão e motivo |
| --- | --- | --- |
| Busca | Índice persistente ou serviço de busca | Usar coleção Vue já carregada. Sem consulta nova ao banco nem índice especulativo. |
| Cópia | Exportar arquivo H2 bruto ou todas as tabelas | Manter cópia física como procedimento operacional; F2 usa DTO JSON versionado só de tarefas locais. Evita acoplamento a arquivos internos e vazamento de credenciais. |
| Importação | Sobrescrever base, mesclar campos ou recriar IDs | Rejeitar no MVP. Aditiva, preservando IDs; conflito bloqueia o arquivo inteiro. |
| Recorrência | Scheduler e regra infinita | Lote finito de tarefas com vínculo de série. Dispensa serviço em segundo plano e torna previsível o conjunto. |
| Checklist | Texto JSON embutido ou nova hierarquia de tarefas | Tabela filha simples: identidade, integridade e atualização de itens sem inventar subtarefas recursivas. |

## Selected schema/query/data design

### F1 — Busca global

Sem mudança no schema. API continua fonte canônica; filtros não alteram dados. Se medições futuras mostrarem que carregar o acervo inteiro causa problema, revisar consulta/paginação a partir dos predicados reais. Esta rodada não define escala suportada.

### F2 — Cópia das tarefas e importação aditiva

1. Envelope JSON com versão de formato, data/hora de geração e coleção de tarefas `LOCAL`. Exportar todos os campos funcionais e IDs; não exportar revisão de concorrência interna como se fosse conteúdo do usuário. Excluir preferências, credenciais e tabelas Google. Datas civis em `YYYY-MM-DD`, horários opcionais em formato explícito compatível com a precisão atual; não converter data civil em UTC.
2. **Guardas técnicas iniciais propostas:** até 5 MiB de arquivo UTF-8 e até 5.000 tarefas por importação, ambos aplicáveis. São limites de segurança do MVP para memória/transação, não capacidade medida nem limite do acervo. Exibir na seleção e validar no servidor, com limite de requisição coerente; não carregar payload ilimitado para depois rejeitá-lo. Exportação não pode truncar o acervo nem oferecer arquivo acima desses limites como restaurável pelo MVP: bloquear com motivo e orientar a cópia operacional H2 com backend parado. O Tester deverá verificar as bordas antes de liberar esses limites.
3. Validar integralmente antes de escrita: versão suportada, estrutura/tipos, UUID, campos obrigatórios, limites existentes, enums, cor, datas/horários e unicidade. Notas e títulos são texto. Duas entradas internas com mesmo ID e conteúdo divergente bloqueiam tudo; entradas internas idênticas podem ser normalizadas uma única vez, com contagem explícita na prévia.
4. Comparação canônica por campos funcionais, sem depender da ordem das propriedades do JSON. Mesmo ID e conteúdo igual é ignorado. Mesmo ID diferente bloqueia a importação inteira. IDs novos são candidatos à inclusão. Não sobrescrever, mesclar ou gerar IDs alternativos.
5. Prévia não grava dados. A confirmação vincula o mesmo arquivo e o mesmo resumo revisado. O servidor revalida o conteúdo, classifica novamente e compara com a prévia; se mudar o resultado, devolve nova revisão antes de gravar. Não confiar em contagens ou flags enviadas pelo cliente.
6. Transação única para todos os itens novos e estruturas relacionadas. Bloquear/revalidar os registros envolvidos durante classificação e aplicação; a chave primária continua protegendo contra inserção concorrente do mesmo ID. Se um ID ausente na prévia aparecer numa corrida, abortar o lote, recalcular e pedir revisão. Não fazer `exists` seguido de `save` sem proteção nem tratar violação de chave como sucesso parcial.
7. Introduzir controle otimista de revisão em tarefas para novas edições: versão aditiva inicial zero, leitura devolve revisão, gravação informa revisão esperada e conflito exige recarga/revisão. Definir o contrato com o Arquiteto; não reusar o PATCH atual enviando só um campo, pois datas/conclusão têm semântica de substituição. Revisão não substitui transação, PK ou revalidação da importação.
8. Retry após timeout usa a mesma identidade dos registros: reaplicar cópia já importada resulta em idênticos ignorados. Não repetir gravação cegamente após erro desconhecido. Exportação deve representar leitura consistente do conjunto e futuros filhos; evitar exportar uma task e seus itens em momentos incompatíveis.

**Compatibilidade:** iniciar formato versionado para o modelo atual. Quando F3/F4 forem liberadas, ampliar a versão para preservar séries e itens; continuar aceitando formatos anteriores com vínculo ausente/checklist vazio. Formato futuro desconhecido é rejeitado sem escrita. Contagens, IDs e conteúdo devem sobreviver à exportação/importação. Vínculos precisam ser validados antes da gravação e não podem se conectar acidentalmente a uma série diferente já existente.

### F3 — Série semanal finita

- Proposta mínima: `hub_task_series` com ID UUID, estado ativo/encerrado, início, quantidade original e identidade da submissão de criação; `hub_task.series_id` opcional com FK. Guardar série mesmo após encerramento para manter vínculo histórico das tarefas restantes. Identidade da submissão deve ter unicidade e conteúdo associado: retry igual retorna o mesmo resultado; chave igual com conteúdo diferente é conflito.
- Gerar de 2 a 52 ocorrências em uma única transação, datas separadas por sete dias civis. Ocorrências mantêm UUID próprio e podem ser reagendadas/concluídas independentemente. Não exigir unicidade `(serie, data)`: reagendamento individual pode fazer datas coincidirem sem serem a mesma tarefa.
- Encerramento consulta e bloqueia a série e os registros candidatos na mesma transação. Remove somente ocorrências **pendentes com data atual estritamente maior que hoje local**, preservando hoje, anteriores, concluídas e vínculos remanescentes. Não usar posição original no lote para decidir; a data pode ter sido editada.
- Contagem exibida precisa corresponder ao conjunto removido. Se revisão/estado mudar entre resumo e confirmação, rejeitar a confirmação antiga e apresentar novo resumo. Ordenar aquisição de locks de maneira consistente; repetir encerramento de série já encerrada é no-op e nunca remove tarefas adicionais depois de reagendamentos posteriores.
- Data de hoje deve vir de uma política local explícita da aplicação, compartilhada com as regras de calendário, sem depender de conversão UTC implícita. Revisar com o Arquiteto o ambiente Docker e o fuso civil usado na operação; nenhuma data de usuário é convertida silenciosamente.
- Não copiar checklist para ocorrências nem liberar criação de checklist em série neste MVP. O vínculo de série precisa sobreviver ao backup, mesmo se algumas ocorrências foram removidas; por isso exportar somente a quantidade originalmente configurada não permite reconstruir o estado atual.

### F4 — Checklist subordinado à tarefa

- Proposta: `hub_task_checklist_item` com ID UUID, `task_id` obrigatório e FK para `hub_task`, texto até 200 caracteres, conclusão booleana e posição estável de criação. Até 20 itens por task, validado no agregado/transação; não confiar somente na interface.
- Não impor unicidade de texto: itens iguais são permitidos. Índice pela FK e unicidade de posição dentro da task atendem à leitura ordenada, com ordem de criação preservada mesmo após remoção. Sem reordenação no MVP.
- Gravar alterações do checklist e a revisão da task na mesma transação. Revisão otimista impede que duas abas substituam silenciosamente o checklist uma da outra. Consulta de lista não deve fazer uma chamada por item; carregar itens no detalhe ou em leitura conjunta apropriada ao contrato. Medir antes de adicionar materializações de progresso.
- `completed` da task não é derivado dos itens. Concluir com pendências depende de confirmação de intenção na interface e operação explícita no servidor; não marcar itens automaticamente. Task concluída recusa edições de checklist até reabrir. Reabrir preserva marcas/itens.
- Exclusão da task remove filhos na mesma transação, por FK/cascade definido e revisado; nenhuma cascata pode apagar outra task ou série. Importação inclui checklist dentro da task, valida IDs/ordem/limites e trata diferenças como conflito da task inteira.

## Migration, backfill, rollback, and restore plan

1. Antes de qualquer futura alteração de persistência, registrar schema/código em uso e produzir cópia física consistente da base com backend parado. Identificar se a base é local ou volume Docker; não misturar as duas nem copiar arquivo H2 ativo.
2. Expandir de modo compatível no mecanismo efetivamente adotado: revisão opcional/default zero em tarefas atuais, tabela de séries, FK opcional e tabela de itens. Tarefas antigas recebem ausência de série e zero itens; não inferir recorrência ou checklist pelo texto das notas.
3. Revisar DDL repetível de `schema.sql`, defaults e FKs antes de executar; se for adotado mecanismo versionado, isso é decisão técnica explícita, com baseline e prova de atualização de base existente. Não executar dois mecanismos conflitantes por acidente.
4. Entregar uma feature por vez, junto à evolução compatível do formato portátil. Manter arquivos antigos importáveis dentro do escopo definido. Não remover colunas/tabelas antigas nesta rodada.
5. Volta de código só é segura se o código anterior tolerar schema aditivo **e preservar estruturas novas nas operações que ainda oferece**. Em particular, excluir uma task por versão antiga exige integridade dos filhos. Se a compatibilidade não for demonstrada, parar escrita e usar plano de recuperação; não voltar código arbitrariamente.
6. Para recuperação física: parar backend, conservar uma cópia do estado atual para diagnóstico, restaurar a cópia consistente no destino correto e subir a versão compatível com ela. Nunca apagar `hub_data` ou usar `down --volumes` como procedimento de rollback. Restaurar cópia antiga pode perder gravações posteriores: levantar delta/impacto e decisão humana antes de substituição; importação aditiva não equivale à recuperação física.
7. Integridade e restauração precisam ser demonstradas pelo Tester em cópia isolada antes de liberação. RPO/RTO ainda não foram definidos; não prometer recuperação sem perda de mudanças posteriores ao backup.

## Monitoring and capacity signals

Registrar contagens de tarefas/itens/séries, bytes do arquivo, duração de prévia/aplicação, conflitos, abortos de transação e falhas de leitura/escrita sem conteúdo de notas, arquivo ou credenciais em logs. Observar crescimento do H2 e tempo de abertura/exportação. Só propor índices adicionais, paginação ou mudança de armazenamento com dados representativos e gargalo demonstrado.

## Tester data checks

Proposta para execução futura; nenhum cenário foi executado pelo DBA.

| Cenário | Verificação de dados |
| --- | --- |
| Cópia fiel | Round-trip em base isolada com sem data, horário, prioridade, notas, conclusão; depois séries e checklist. Comparar IDs e conteúdo, não apenas total. Confirmar ausência de preferências/segredos/Google. |
| Importação inválida | JSON quebrado, versão futura, enum/data/UUID inválido, limites de bytes/quantidade e duplicidade divergente: zero alteração. |
| Importação idêntica/conflitante | Reimportar idêntico não duplica; um conflito no meio de arquivo com novos registros bloqueia todos. |
| Falha/concorrência | Falha no meio do lote reverte tudo; mutação relevante entre prévia e confirmação exige revisão; inserção simultânea de mesmo ID não gera escrita parcial. |
| Série | 2 e 52 ocorrências; rejeitar 1/53; fronteira mês/ano; retry; reapresentação após timeout; concluir/reagendar uma não altera outras. |
| Encerramento | Preservar hoje, passadas e futuras concluídas; excluir só futuras pendentes; cancelar não altera; contagem concorrente exige nova confirmação; repetir é no-op. |
| Checklist | 20 itens/200 caracteres e bordas; identidade/ordem estáveis; dois textos iguais; task concluída somente leitura; progresso não conclui task; reabrir mantém itens. |
| Concorrência de edição | Duas abas com mesma revisão: a segunda recebe conflito sem sobrescrever silenciosamente. |
| Migração/recuperação | Base antiga passa à versão nova sem perda; DDL repetido é coerente; exclusão mantém integridade; restauração isolada recupera IDs/conteúdo. |

## Decision

**approved with conditions** para o planejamento, sem implementação de banco autorizada nesta entrega. Condições: fechar formato/contratos de revisão e prévia com o Arquiteto, confirmar guardas técnicas por prova focada, resolver política de data local, registrar DDL compatível e provar recuperação/atomicidade com Tester antes da liberação. Não há divergência com o PO; preferências estão expressamente excluídas de F2, e a versão inicial da arquitetura precisa refletir esse recorte.
