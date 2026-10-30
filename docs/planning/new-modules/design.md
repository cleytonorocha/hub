# Seis novos módulos — proposta de experiência

**UX-HUB-02 · Designer · Nível 2 · 28/10/2026.** Entrada: [PO-HUB-02](../po-new-modules.md). Decisão: explorar seis espaços pessoais novos, com **três telas conectadas por módulo, 18 telas ao todo**, mantendo tarefas/calendário úteis e independentes. A ordem recomendada pelo PO para validação começa em notas, finanças e hábitos.

Esta rodada é descoberta visual. Os [artefatos anteriores de melhorias do núcleo](../design.md) permanecem separados e preservados. Não há autorização de implementação, aprovação técnica implícita ou pesquisa com usuários demonstrada. `/caveman-lite` não foi executado: comando indisponível nesta atuação.

## Artefatos

- [Galeria das 18 telas](screens/index.html), com links para ampliar cada imagem e percorrer suas três etapas.
- [Prancha geral dos seis fluxos](screens/overview.svg).
- [Fonte editável dos mockups](screens/generate.cjs), que gera somente SVG e HTML de planejamento.

Os controles desenhados são estáticos. Apenas os links da galeria navegam entre artefatos. Todas as pessoas, textos, valores, datas, históricos e documentos são fictícios; nenhum arquivo pessoal foi acessado ou anexado. Referências: `front/src/style.css` e a captura existente `docs/screenshots/overview.png`, estudados na rodada UX-HUB-01. Não foi realizada nova captura nem validação por navegador.

## Fluxos e inventário

| ID | Tela | Ação principal e resultado propostos |
| --- | --- | --- |
| N1-T1 | [Biblioteca de notas](screens/N1-T1.svg) | Buscar título/corpo, filtrar etiquetas, fixadas/arquivadas ou criar nota independente. |
| N1-T2 | [Editor de nota](screens/N1-T2.svg) | Título, corpo, etiquetas e seleção opcional de tarefa; salvar explicitamente e perceber edição pendente. |
| N1-T3 | [Nota e contexto](screens/N1-T3.svg) | Consultar, editar, fixar/arquivar e abrir tarefa relacionada; voltar preservando busca. Remover relação não exclui registros. |
| N2-T1 | [Resumo mensal](screens/N2-T1.svg) | Receitas, despesas e resultado do mês com distribuição textual por categoria. Valores manuais em BRL. |
| N2-T2 | [Novo lançamento](screens/N2-T2.svg) | Receita/despesa, descrição, valor positivo até duas casas, data e categoria; prévia textual do efeito. |
| N2-T3 | [Extrato e revisão](screens/N2-T3.svg) | Filtrar tipo/categoria, consultar entradas e abrir edição/exclusão com confirmação, retornando ao mês correto. |
| N3-T1 | [Hábitos de hoje](screens/N3-T1.svg) | Ver apenas os hábitos previstos hoje; marcar/desmarcar sem gerar tarefas ou culpar por ausência. |
| N3-T2 | [Configurar hábito](screens/N3-T2.svg) | Nome, dias, início e resumo natural da frequência; editar/pausar/retomar como estados do mesmo formulário. |
| N3-T3 | [Histórico semanal](screens/N3-T3.svg) | Distinguir feito, previsto, não previsto, antes do início e pausa; mostrar registros e denominador claramente rotulado. |
| N4-T1 | [Preparar foco](screens/N4-T1.svg) | Objetivo, duração positiva e tarefa opcional; recuperar sessão existente antes de iniciar outra. |
| N4-T2 | [Sessão ativa](screens/N4-T2.svg) | Contagem, pausar/retomar e encerrar. Pausa não conta como foco; suspensão/reabertura exige conferência honesta. |
| N4-T3 | [Resumo e histórico](screens/N4-T3.svg) | Comparar planejado/registrado e tipo de término; nota opcional e sessão anterior, sem concluir tarefa. |
| N5-T1 | [Fila de conteúdos](screens/N5-T1.svg) | Lista por “A ler”, “Em andamento” e “Concluído”; filtro por tipo/busca como estados da mesma tela. |
| N5-T2 | [Cadastrar conteúdo](screens/N5-T2.svg) | Tipo/título, autor/origem, etiquetas e link opcional; exemplo de livro físico sem link. |
| N5-T3 | [Progresso e aprendizado](screens/N5-T3.svg) | Marcador textual, estado e nota; concluir/arquivar conscientemente. Sem percentual ou consumo presumido. |
| N6-T1 | [Catálogo documental](screens/N6-T1.svg) | Buscar por nome/categoria e identificar validade informada ou ausência dela; sem sugerir cofre. |
| N6-T2 | [Registrar documento](screens/N6-T2.svg) | Metadados, validade opcional e um anexo opcional PDF/JPG/PNG, com limite visível antes da seleção. |
| N6-T3 | [Documento e renovação](screens/N6-T3.svg) | Consultar documento e revisar criação de tarefa de renovação. Criar/concluir tarefa não altera validade nem renova o documento. |

Cada trio representa uma jornada, não três alternativas de estilo. Estados de erro, vazio, edição e confirmação integram essas telas sem acrescentar telas principais ao inventário.

## Conteúdo e invariantes por módulo

**N1 — Notas:** etiquetas livres substituem pasta obrigatória. Corpo pode começar vazio; título é obrigatório. Arquivar apenas retira da visão padrão; excluir exige confirmação específica. Saída com alterações pendentes oferece continuar/descartar. Tarefa removida aparece como vínculo indisponível, preservando a nota. Não prometer anexos, versionamento, captura da web ou editor avançado.

**N2 — Finanças:** “resultado do mês” significa receitas menos despesas registradas; nunca saldo bancário. O exemplo da tela é coerente: R$ 4.800,00 − R$ 2.150,00 = R$ 2.650,00; extrato e categorias compõem esses mesmos totais. Ausência de lançamentos é “nenhum lançamento registrado”, sem inferir renda/gasto real zero. Valor inválido, zero, negativo ou precisão adicional bloqueia salvar sem arredondamento silencioso. Alterar data corrige ambos os meses. Duas despesas iguais podem ser legítimas. Sem bancos, pagamentos ou recomendações.

**N3 — Hábitos:** dia local; só dias previstos desde o início e não pausados entram no denominador. A semana ilustrada tem início em quarta 28/10 e previsão qua/sex: o mockup rotula **“registro / dias previstos na semana: 1 de 2”**, mantendo sexta futura não registrável. Não apresentar esse recorte como percentual de execução até hoje. Dias antes do início ficam fora. “Ainda não registrado” para hoje, “não registrado” para passado elegível, futuro bloqueado; sem dias elegíveis usa mensagem própria. Pausa a partir de hoje preserva registros já feitos, exclui oportunidades pausadas e não apaga histórico. Mudança da frequência vale para hoje ainda não registrado e futuro. Pausar/retomar e correção de dias passados elegíveis devem ficar acessíveis na implementação; sem streak ou ranking.

**N4 — Foco:** uma sessão ativa; pausar muda estado e botão para “Retomar”, excluindo pausa da duração em foco. Ao atingir o planejado, pedir encerramento e não iniciar ciclo automático. Recuperação deve permitir recuperar/encerrar sessão existente. Suspensão exige conferir o tempo; ajuste manual precisa aparecer identificado como ajustado. Relógio não prova atenção contínua. Histórico distingue término normal, antecipado e ajustado; erro mantém resumo para nova tentativa. Não bloquear outros apps, monitorar janelas, capturar tela ou prometer alarmes com Hub fechado.

**N5 — Leitura:** o livro sem URL é um caso principal. Título e tipo obrigatórios; marcador textual como “capítulo 3, página 72” não requer denominador. Nota pessoal pertence ao registro de conteúdo e não duplica silenciosamente nota N1. Arquivar preserva notas e registro. Quando houver URL web válida, o link identifica a saída do Hub e abre somente mediante ação; cadastrar não busca metadados nem acessa a rede. Links indisponíveis não apagam registros. Sem download, player embutido ou sincronização.

**N6 — Documentos:** não pedir CPF ou identificador secreto para catalogar. Sem validade informada é diferente de documento que nunca vence; ontem = vencido, hoje = vence hoje, amanhã = válido. Anexo é opcional; metadados sozinhos já têm valor. DBA propôs **um anexo e limite de 8 MiB**, PDF/JPG/PNG, como guardrail de MVP sujeito à aprovação técnica, não capacidade medida. Seleção deve mostrar tipo/limite e rejeitar arquivo inválido sem remover o anterior ou a entrada. Remover anexo e excluir catálogo são ações distintas, ambas com confirmação de efeito. Mostrar tarefa de renovação já associada para evitar repetição acidental e permitir nova somente por escolha. Nenhuma promessa de cofre, criptografia, OCR, assinatura ou renovação automática. Cadastro sem anexos é recorte válido se proteção/recuperação de arquivos não estiverem fechadas.

## Estados alternativos e recuperação

| Estado | Orientação de interface |
| --- | --- |
| Acervo vazio | Explicar o módulo e oferecer primeiro registro; nunca preencher com dados fictícios apresentados como reais. |
| Sem correspondências | Manter busca/filtros, distinguir de acervo vazio e oferecer limpar filtros. |
| Carregando | Informar atividade sem declarar “não há dados”; não permitir envio repetido durante operação. |
| Erro de campo | Texto junto ao campo, motivo concreto, associação acessível e entrada preservada. |
| Erro de gravação | Não anunciar sucesso; preservar formulário/resumo, estado confirmado anterior e nova tentativa segura. |
| Edição não salva | Avisar antes de abandonar, oferecendo continuar ou descartar; cancelar não grava. |
| Ação destrutiva | Nome e efeito explícitos, registro afetado e cancelamento visível. Não presumir exclusão de vínculos em cascata. |
| Relação indisponível | Manter o registro principal e permitir remover somente a relação. |
| Sem acesso real | Informar a recusa da API sem inventar conta/login/compartilhamento que o projeto não tem. |

## Layout, tokens e acessibilidade

Reutilizar identidade da rodada anterior: fundo `#090c19`, navegação `#0b0e1f`, superfície `#111527`, borda `#292c43`, texto `#f3f1fb`, secundário `#aeb1c8`, acento `#a986ff` e foco `#c3a7ff`. Inter/Segoe UI/sistema, corpo 16–18 px e metadados 14 px. A lista “Seus espaços” dos mockups comunica o portfólio possível; não recomenda adicionar seis destinos obrigatórios antes de selecionar o primeiro módulo.

SVG em 1440 × 1000 é referência de composição, sem demonstrar responsividade. Na implementação: desktop com cartões/painéis, tablet com navegação recolhida e mobile em coluna única. Formulários devem refluir a 320 px/zoom 200%; grades semanais precisam alternativa textual em lista, sem esconder dias ou ações. Sessão de foco mantém intenção/estado/pausa legíveis sem depender do círculo. Finanças mantém valores com sinais e rótulos, não só cores.

Requisitos para todos os módulos: controles nativos rotulados, ordem de teclado lógica, alvos de pelo menos 44 × 44 px, foco visível e restaurado ao fechar diálogo/detalhe, estados anunciados após confirmação real, erros associados aos campos, landmarks e um título principal. Diálogo com nome, descrição, contenção/restauração de foco e cancelamento. Texto normal com contraste mínimo 4,5:1 e elementos/grandes textos 3:1; medir na implementação. Respeitar redução de movimento. Valores, títulos e notas são texto literal, nunca HTML executável.

## Proposta de validação para Tester e limites

Após implementação aprovada: executar matrizes N1–N6 do PO-HUB-02; percorrer cada fluxo com teclado e leitor de tela; conferir mobile, zoom, textos longos, erro e cancelamento; comparar totais financeiros; conferir dias locais/pausas; recuperar sessão suspensa; testar livro sem rede; verificar rejeição de anexo e independência entre tarefa/documento. Confirmar recuperação de cada novo tipo de dado antes de liberação: a cópia de tarefas de PO-HUB-01 não os cobre automaticamente.

**Implementação:** nenhum código do aplicativo alterado. **Evidência desta entrega:** leitura dos requisitos e geração de artefatos vetoriais; sem build, testes, navegador, QA de implementação ou medição de contraste. **Riscos abertos:** valor ainda hipotético, navegação/portfólio excessivos, proteção/recuperação dos dados pessoais e definição final de guardrails técnicos. Arquiteto/DBA decidem viabilidade e aprovação técnica; PO decide o primeiro módulo após revisão com usuário.
