# Dados dos novos módulos — DATA-HUB-02

Papel: DBA da EquipeTecnica · Nível 2 · 28/10/2026. Revisão de descoberta baseada em [PO-HUB-02](po-new-modules.md), [ARCH-HUB-02](architecture-new-modules.md), README e evidências de persistência da rodada anterior. Não foi executado build, teste, navegador ou alteração no aplicativo/banco. Caveman Lite indisponível, não executado.

**Decision: approved with conditions para descoberta e desenho dos seis módulos.** A decisão não congela schema nem autoriza implementação conjunta. Selecionar um módulo e fechar seus contratos, migração e recuperação antes de código.

## Evidência e limites

O sistema observado usa H2 em arquivo, JPA sem geração automática de schema e inicialização por `schema.sql`. A API atual lista tarefas inteiras e o modelo `Task` representa tarefas. As propostas N1–N6 têm ciclo próprio e não devem ser armazenadas artificialmente nessa tabela. Não há volumes de uso, crescimento, latências ou objetivos de recuperação medidos; não recomendar novos bancos, particionamento, filas ou serviços de armazenamento por antecipação.

O backup portátil F2 é proposta de cópia **somente de tarefas**. Não representa cópia dos novos módulos nem de anexos. A recuperação de cada domínio selecionado faz parte de sua futura entrega.

## Direção por módulo

| Módulo | Dados e invariantes mínimos | Condição para implementação |
| --- | --- | --- |
| **N1 — Notas** | Identidade própria, título/corpo, etiquetas, fixada/arquivada, data de alteração e revisão esperada. Corpo é texto, sem HTML ativo. Associação à task é opcional e não é propriedade da tarefa. Arquivar preserva conteúdo. | Salvamento explícito atômico e conflito entre abas sem sobrescrita silenciosa. Task excluída não apaga nota; mostrar vínculo indisponível/removível conforme PO. Não prometer histórico de versões. |
| **N2 — Finanças manuais BRL** | Lançamento próprio com tipo receita/despesa, valor positivo, data civil, descrição e categoria. `BigDecimal` no Java e `DECIMAL` de escala 2 no banco, com precisão/limite superior explícitos no contrato escolhido; nunca float/double. Tipo determina sinal, sem valor negativo. | Rejeitar precisão extra, zero e negativo sem arredondar silenciosamente. Transporte decimal explícito e somas canônicas no servidor, sem cálculo financeiro por ponto flutuante JS. Resumo e extrato usam mesmo período/conjunto. Duas despesas iguais podem ser legítimas; deduplicação só pela identidade da mesma submissão, não pelo conteúdo. |
| **N3 — Hábitos** | Hábito e check-ins próprios, unicidade por hábito/data civil. Preservar agenda e períodos de pausa historicamente, com vigência; sobrescrever somente a agenda atual faria o denominador passado mudar. | Marcar/desmarcar é idempotente; futuro bloqueado. Mudança da agenda vale para hoje ainda não registrado e futuro. Pausa a partir de hoje mantém registros já feitos; período pausado não vira falta. Contar oportunidades pela agenda vigente em cada dia, preservando marca anterior à pausa; semana sem oportunidade não é 0%. Fechar regra com exemplos antes de schema. |
| **N4 — Foco** | Sessão própria com objetivo, duração planejada, estado e instantes de início/pausa/retomada/encerramento ou acumuladores equivalentes. Uma sessão ativa/pausada por instalação local; task opcional. | Transições atômicas e concorrência protegida entre abas. Pausas não contam; não gravar cada segundo. Relógio decorrido não comprova trabalho: suspensão requer conferência e eventual duração ajustada identificada como tal. Retry de encerramento não duplica sessão. Definir limites positivos de duração no contrato, sem inventar medida de produtividade. |
| **N5 — Leitura/conteúdos** | Item próprio, tipo livro/artigo/vídeo, título, origem opcional, URL opcional, estado, etiquetas, marcador textual, nota e arquivamento. Títulos repetidos são permitidos. | Marcador como “capítulo 3” ou “12:30”, sem percentual inventado. Não exige link nem consulta externa. URL aceita apenas esquemas web permitidos e não é executada como conteúdo; dados e notas continuam acessíveis sem rede. Não exigir N1 para uma nota simples do item. |
| **N6 — Documentos** | Documento próprio com título, categoria, observação e validade civil opcionais; um anexo PDF/JPG/PNG opcional e vínculo explícito com task de renovação. Não exigir CPF/outros identificadores. | Renovação apenas cria task após revisão; não muda validade, não renova documento e não cria versionamento documental. Excluir documento/anexo são ações distintas e confirmadas. Anexo inválido/falha de upload preserva metadados e arquivo anterior. Catálogo sem anexos é primeiro recorte válido. |

## Datas, relações e concorrência

- Usar **datas civis** para lançamento financeiro, validade, agenda e check-in. Instantes de auditoria e foco são armazenados com referência temporal inequívoca. O fuso civil da instalação deve estar explícito e ser aplicado pelo servidor e pela interface; Docker e navegador não podem decidir “hoje” de formas divergentes. Não converter datas civis implicitamente para UTC.
- Vínculos opcionais não autorizam cascata de exclusão entre domínios. Apagar uma task não apaga nota, sessão ou documento. Preservar identidade de referência indisponível quando a UX precisa informá-la; definir a estratégia de FK/referência no módulo escolhido, sem copiar estado da task como segunda fonte canônica.
- Revisão esperada/controle otimista em registros editáveis e transações nas operações compostas. Sucesso só após gravação confirmada; conflito pede revisão. IDs estáveis e identidade de submissão, quando necessária, protegem retries sem proibir registros legítimos iguais. Não presumir que o PATCH de Task serve aos novos domínios.
- Para finanças, editar data deve remover o efeito do mês anterior e incluí-lo no novo; exclusão/correção atualiza exatamente o conjunto que fundamenta os totais. O resultado é receitas menos despesas do mês, nunca saldo bancário.

## Proteção e recuperação de anexos N6

**Guarda técnica proposta para o mockup/MVP:** um arquivo por documento, máximo **8 MiB**, PDF/JPG/PNG. É limite de segurança inicial, não capacidade medida. Validar no servidor antes de publicação, verificando bytes/tipo real e não somente extensão ou cabeçalho informado. A seleção deve mostrar os limites; arquivo rejeitado não substitui o anterior.

Arquivo recebe identificador/nome interno gerido pelo backend, fora da raiz pública. Nome original é metadado, nunca caminho de escrita. Download exige acesso autorizado por ID, sem caminho fornecido pelo cliente; não gerar prévia ativa por padrão. Não chamar isso de cofre, criptografia ou backup seguro já existente.

Banco e sistema de arquivos não são uma transação única. A futura implementação deve definir publicação em etapas: receber em área temporária, validar/gravar, confirmar referência e só então disponibilizar; falhas preservam o arquivo anterior e permitem limpar temporários/órfãos de modo controlado. Na remoção, coordenar referência e descarte sem apagar arquivo ainda referenciado. Não inventar integração de object storage para resolver um recorte local.

Cópia recuperável precisa reunir banco, manifesto de referências e os mesmos arquivos, em ponto consistente. Para o primeiro recorte local, parar as escritas/backend para produzir cópia conjunta é alternativa simples. Restaurar somente H2 deixaria referências quebradas; restaurar só anexos perderia catálogo/relações. Tester deverá comprovar restauração conjunta em ambiente isolado antes de liberar anexos.

## Migração, rollback e privacidade

1. Expandir schema somente do módulo escolhido. Tarefas existentes não mudam de significado. Usar mecanismo realmente adotado no projeto; não presumir Flyway por existir uma pasta `db/migration`.
2. Antes da migração, cópia física consistente da base correta com backend parado; se N6 existir, incluir arquivos. Bases local e Docker são independentes. Nunca usar apagar volume como rollback.
3. Definir formato de exportação/recuperação do módulo e seu escopo antes de prometer “backup do Hub”. Formato deve preservar IDs, relações e precisão, sem exportar segredos por efeito colateral.
4. Volta de código só com compatibilidade comprovada e preservação dos novos dados. Se exigir recuperação física, conservar cópia do estado atual e avaliar alterações feitas após o backup antes de restaurar: uma cópia antiga pode perder gravações recentes. Não estabelecer RPO/RTO sem decisão e prova.
5. Dados financeiros, hábitos, textos e documentos são pessoais. Não registrar conteúdo/valores/arquivos em logs nem enviar a terceiros por padrão. Localhost não equivale a criptografia; acesso às novas rotas e ao diretório de dados requer revisão arquitetural explícita. Não criar login ou criptografia fictícios nos mockups.

## Propostas de verificação para Tester

| Área | Prova futura mínima |
| --- | --- |
| N1 | Reabrir conteúdo/etiquetas; erro preserva texto; edição concorrente rejeita sobrescrita; excluir task não exclui nota. |
| N2 | R$ 1.000,00 − R$ 125,50 = R$ 874,50; editar/mudar mês mantém extrato e resumo coerentes; rejeitar casas extras e limites; retry não duplica mesma ação. |
| N3 | Agenda seg/qua/sex, início, pausa/retomada, mudança no meio da semana e marca antes da pausa; mesmo check-in duas vezes; futuro; semana sem previstos. |
| N4 | Duas abas, recarga, pausa, suspensão, encerramento antecipado/ajustado e retry; nenhuma conclusão automática de task. |
| N5 | Livro sem URL, URL inválida, título repetido, marcador textual e arquivo sem rede; arquivamento conserva nota. |
| N6 | Ontem/hoje/amanhã/sem validade; arquivo inválido, excesso, falha preservando anterior, acesso por ID, exclusão cancelada e restauração conjunta. |
| Comum | Base antiga migra sem perda; cópia/restauração preserva cada domínio; erro não parece vazio; regressão de tarefas/calendário. |

## Decisão e riscos abertos

**approved with conditions para descoberta/design.** Condições para uma futura implementação: selecionar um módulo, fechar limites/contratos de escrita e datas, definir recuperação e revisar acesso; N6 precisa ainda de plano coordenado de arquivos. Não há divergência de produto com PO-HUB-02. Revisar no ARCH-HUB-02 a agenda histórica/pausas de N3 e o fato de renovação N6 não alterar o documento. Sem métricas de capacidade e sem execução de testes nesta rodada.
