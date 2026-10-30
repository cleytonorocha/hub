# Desenho das próximas features do Hub

Handoff **UX-HUB-01** · Designer · Nível 2 · 28/10/2026. Entrada: **PO-HUB-01**, em [po-features.md](po-features.md). Saída: três telas conectadas para cada uma das quatro features, **12 telas principais**. Estados alternativos pertencem às mesmas telas.

**Decisão:** manter a identidade existente e propor fluxos pequenos, com resultado e recuperação explícitos. As propostas seguem o escopo final do PO; não são autorização técnica nem implementação. Não foi executado `/caveman-lite`: comando indisponível nesta atuação.

## Artefatos e referências

- [Galeria navegável das 12 telas](screens/index.html): ampliar os SVG e percorrer as etapas por links. Somente a navegação da galeria funciona; os controles desenhados são estáticos.
- [Prancha com os 12 mockups](screens/overview.svg): leitura do conjunto; abra as telas individuais para detalhes.
- [Fonte editável dos vetores](screens/generate.cjs): gera os SVG e a galeria, sem carregar o aplicativo ou acessar dados reais.
- Evidência visual estudada: `docs/screenshots/overview.png`. Referência de tokens e estrutura: `front/src/style.css`. Não houve captura nova, pesquisa com usuários, QA de navegador ou execução do aplicativo.

Todos os registros, contagens e datas são **dados fictícios**. A tela F3-T3 adota explicitamente 09/11/2026 como “hoje” de seu cenário, para exemplificar a preservação de uma ocorrência de hoje; não altera a data atual de planejamento.

## Fluxos e inventário das telas

| Feature / etapa | Tela | Entrada, ação e saída propostas |
| --- | --- | --- |
| F1-T1 | [Central de busca](screens/F1-T1.svg) | Entrar pelo Calendário e buscar no acervo inteiro, incluindo futuro, sem data e concluídas. Consulta vazia mantém escopo e total visíveis. |
| F1-T2 | [Resultados filtrados](screens/F1-T2.svg) | Consulta “planejamento”, chips “Pendente” e “Trabalho”, prioridade e período. Abrir resultado ou retirar filtros sem perder o termo. |
| F1-T3 | [Detalhe e retorno](screens/F1-T3.svg) | Consultar/alterar propriedades já suportadas; título não é campo editável. Voltar restaura consulta, filtros e posição. Abrir no calendário só para tarefa datada. |
| F2-T1 | [Cópia das tarefas](screens/F2-T1.svg) | Configurações > Dados. Mostrar quantidade, conteúdo incluído/excluído; preparar cópia para o navegador oferecer salvamento. Não afirmar gravação física confirmada. |
| F2-T2 | [Selecionar e validar arquivo](screens/F2-T2.svg) | Escolher JSON do Hub, identificar data/versão/quantidade e validar antes de qualquer gravação. Corrigir arquivo ou seguir para prévia. |
| F2-T3 | [Revisar e adicionar](screens/F2-T3.svg) | Mostrar novas/idênticas/conflitantes e amostra; “Adicionar N tarefas” somente sem bloqueios. Revalidar se o acervo mudar e exigir revisão da nova prévia. |
| F3-T1 | [Configurar repetição](screens/F3-T1.svg) | Nova Task: início obrigatório, horário opcional, toda semana e quantidade de 2–52. Dia deriva do início; última data é calculada. |
| F3-T2 | [Revisar ocorrências](screens/F3-T2.svg) | Listar todas as datas previstas antes de criar; voltar preserva entradas; criação explícita do conjunto finito. |
| F3-T3 | [Gerenciar série](screens/F3-T3.svg) | Lista de ocorrências com acesso individual. Estado de confirmação de encerramento mostra exatamente as duas futuras pendentes a remover e o que permanece. |
| F4-T1 | [Editar checklist](screens/F4-T1.svg) | Adicionar ao final, editar texto e remover com confirmação; limites visíveis de 20 itens e 200 caracteres por item. |
| F4-T2 | [Executar checklist](screens/F4-T2.svg) | Exemplo de 2 de 4 itens marcados. Marcação e conclusão da tarefa são independentes; confirmar salvamento por item e recuperar falha. |
| F4-T3 | [Revisar conclusão](screens/F4-T3.svg) | Confirmação com os dois itens pendentes, “Voltar ao checklist” e “Concluir mesmo assim”. Depois, manter 2 de 4 em leitura até “Reabrir tarefa”. |

### Estados alternativos vinculados às telas

| Tela(s) | Estado | Conteúdo e recuperação |
| --- | --- | --- |
| F1-T1/T2 | Sem tarefas | “Você ainda não tem tarefas locais”; oferecer criar uma tarefa com o fluxo existente. Não simular resultados. |
| F1-T2 | Sem correspondências | “Nenhuma tarefa encontrada”; manter termo/filtros e oferecer limpar filtros. Não confundir com acervo vazio. |
| F1-T2 | Período inválido | Erro junto às datas: início não pode ser posterior ao fim. Intervalo inclusivo e “Somente sem data” mutuamente exclusivos. |
| F1-T1/T2 | Carregando/erro | Mensagem de carregamento; se falhar, “Não foi possível carregar as tarefas” + “Tentar novamente”. Resultados antigos, quando mantidos, identificados como desatualizados. |
| F1-T3 | Registro removido | Informar ausência sem mostrar dados antigos como atuais; voltar aos resultados e atualizá-los. |
| F2-T1 | Preparando/pronto/erro | Ação em progresso sem repetição; “Arquivo pronto para salvar” com total e data/hora. Erro permite tentar novamente. A cópia não altera o acervo. |
| F2-T2 | Arquivo incompatível/inválido/grande | Motivo específico e “Escolher outro arquivo”; nada gravado. Mostrar limite técnico aprovado antes da seleção. |
| F2-T3 | Duplicado idêntico | Mesmo ID e mesmo conteúdo: ignorado, contagem explícita. Se não houver novos itens, explicar que nenhuma adição é necessária. |
| F2-T3 | Conflito | Mesmo ID com conteúdo diferente: bloquear o arquivo inteiro. Mostrar quais registros conflitam, preservar acervo e oferecer cancelar/escolher outro. Não resolver alterando IDs automaticamente. |
| F2-T3 | Concorrência/falha | Se o acervo mudar, atualizar contagens e exigir nova revisão quando o resultado mudar. Falha preserva tudo; nenhum sucesso parcial. |
| F3-T1 | Campo inválido | Título vazio, início ausente ou quantidade fora de 2–52 impedem avanço; mensagem junto ao campo e foco no primeiro erro. |
| F3-T2 | Salvando/falha | Evitar clique repetido; erro preserva parâmetros e prévia, sem série parcial. |
| F3-T3 | Encerrar | Confirmar quantidade e datas estritamente posteriores ao dia local atual. Preservar hoje/passadas/concluídas. Cancelar não remove. Se o resumo mudar, exigir revisão. |
| F3-T3 | Nada a encerrar/já encerrada | “Não há ocorrências futuras pendentes para remover”; sem ação destrutiva ativa. Repetição não remove novos registros. |
| F4-T1 | Item vazio/limite | Texto em branco após espaços mostra erro; contador de caracteres; no 20º item, explicar limite e impedir novo item, sem perder entradas. |
| F4-T1 | Remoção | Confirmação com texto do item e estado atual; “Manter item” / “Remover item”. Cancelamento preserva item e marca. |
| F4-T1/T2 | Salvando/falha | Entrada preservada; sinalizar “Salvando…” e depois sucesso confirmado. Em erro, restaurar estado confirmado anterior e oferecer repetição segura. |
| F4-T2/T3 | Todos marcados | Progresso completo não conclui a tarefa; ação explícita permanece necessária. Sem confirmação por itens pendentes quando não há pendentes. |
| F4-T3 | Concluída/reabrir | Checklist preservado e somente leitura, “Tarefa concluída” + “Reabrir tarefa”. Ao reabrir, marcas e texto permanecem. |
| F4-T1 | Tarefa de série | Não oferecer criação de checklist em ocorrência de série no MVP; explicar limitação, sem copiar/propagar itens implicitamente. |

## Direção visual, componentes e conteúdo

Preservar o shell do Hub: navegação lateral de 244 px no desktop, conteúdo com respiro, superfícies escuras e acento roxo. As telas são painéis vetoriais de 1440 × 1000, não páginas de produção. Formulários e detalhes reutilizam os padrões de composição existentes quando forem implementados; não introduzir uma segunda navegação global.

| Token / componente | Direção proposta |
| --- | --- |
| Fundo / navegação | `#090c19` / `#0b0e1f`, coerentes com o CSS existente. |
| Superfície / borda | `#111527` / `#292c43`; não usar borda sutil como único sinal de foco ou de campo editável. |
| Texto / secundário | `#f3f1fb` / `#aeb1c8`. |
| Acento / foco | `#a986ff` / `#c3a7ff`; botão de ação primária com texto escuro `#171025`. |
| Confirmação / atenção | Verde `#84dfbd`, âmbar `#f4c781`; sempre acompanhados por texto/ícone identificável. |
| Tipografia | Inter com fallback Segoe UI/sistema; título 28–32 px, seção 20–24 px, corpo 16–18 px, metadados 14 px. |
| Espaçamento | Base de 8 px; grupos separados por 24–32 px; cartões com 24–28 px de respiro. |
| Formulários | Rótulos visíveis acima do campo, indicação textual de opcional/limite, mensagem de erro associada ao campo. |
| Resultados | Nome, data/sem data, categoria, prioridade e conclusão legíveis. Nunca usar cor como única indicação de estado. |
| Confirmações | Título com ação concreta, quantidade afetada e cancelamento visível. Não usar “OK” genérico em remoção/conclusão com pendências. |

“Cópia das tarefas” substitui qualquer promessa de backup completo da instalação. F2 exclui preferências, credenciais e downloads. Dados de série/checklist só podem aparecer na versão de cópia que os suporta. O registro DATA-HUB-01 do DBA propõe os guardrails de **5 MiB e 5.000 tarefas por arquivo**, ambos aplicáveis; a seleção exibe esses limites como proposta de MVP, não capacidade medida. Arquivo acima de qualquer limite recebe erro recuperável. Exportação acima do limite deve bloquear com explicação, nunca truncar o acervo, e apontar a alternativa operacional documentada de cópia física do H2 com backend parado.

## Responsividade e acessibilidade a implementar

- A partir de 1024 px: shell lateral e painel de filtros contextual. Entre 768–1023 px: navegação recolhida e filtros empilhados. Abaixo de 768 px: coluna única, navegação pelo padrão móvel existente, filtros em painel próprio e botões sem sobreposição.
- A 320 px e zoom de 200%, formulários devem refluir sem rolagem horizontal. A lista de ocorrências pode rolar verticalmente; nenhuma data da prévia pode ficar inacessível. Em mobile o detalhe ocupa a área de conteúdo, preservando a volta.
- Alvos interativos com pelo menos 44 × 44 px; áreas de remover e chips devem ser ampliadas sem depender de um “×” pequeno. Manter espaçamento entre ações de cancelamento e confirmação.
- Um `h1` por tela, landmarks, lista semântica de resultados/ocorrências/itens e ordem de tabulação equivalente à visual. Nenhum cartão depende de clique sem alternativa de teclado.
- Buscar com rótulo acessível; filtros como controles nativos rotulados; checkbox com nome completo do item. Progresso tem texto “2 de 4”, independentemente da barra gráfica.
- Foco visível de pelo menos 2 px e contraste suficiente; evitar que cabeçalhos fixos ocultem o foco. Verificar WCAG AA: 4,5:1 para texto normal e 3:1 para texto grande e elementos de interface. Isso é requisito, não medição já executada.
- Diálogos com nome/descrição, foco inicial seguro, contenção de foco, fechamento por Escape quando não houver operação irreversível em andamento, e restauração ao acionador. “Voltar” também restaura posição/contexto da busca.
- Erros conectados por `aria-describedby` e campo marcado inválido. Salvar/exportar/importar anuncia atividade e resultado confirmado com região de status; falha anunciada sem apagar a entrada. Não afirmar persistência antes da confirmação do backend.
- Respeitar redução de movimento; nenhuma informação depende de animação. Títulos/notas do arquivo são texto literal, jamais HTML executável.

## Proposta de validação para Tester

Executar somente após implementação aprovada por Arquiteto/DBA e entregue pelo Executor. Usar as matrizes F1–F4 do PO-HUB-01 com `@Navegador` e acrescentar:

1. Percorrer cada fluxo por teclado, incluindo erro, cancelamento e retorno. Conferir foco e ausência de armadilha fora de diálogos.
2. Conferir 320/768/1024/1440 px, zoom 200%, títulos longos, observações extensas e 52 ocorrências na prévia.
3. Medir contraste real dos componentes produzidos e verificar alvo de 44 px; os vetores não demonstram conformidade da implementação.
4. Busca global deve encontrar futuro, concluídas e sem data; painel de filtros e escopo devem permanecer claros após retorno do detalhe.
5. Importação deve apresentar bloqueio integral com apenas um conflito; cancelar/falhar não muda registros; nova revisão quando o conjunto local muda. Conferir ausência de preferências/credenciais no arquivo.
6. Encerrar série em virada de dia deve recalcular o recorte antes de confirmar; preservar hoje/passadas/concluídas e dizer quantas foram realmente removidas.
7. Checklist deve preservar itens após falha/reabertura; confirmar conclusão com pendências não marca itens; concluída entra em leitura até reabrir.

## Registro de decisão, limites e riscos

- **Decisão de UX:** fluxos com três telas por feature; operações de perda/remoção e conclusão com pendências têm revisão explícita. Não criar variantes nem novas features neste inventário.
- **Reconciliação com PO final:** F2 contém somente tarefas e conflito bloqueia todo o arquivo; F3 usa quantidade 2–52 com fim calculado; F4 mostra 2 de 4, limites 20/200 e leitura após conclusão. Checklist + série fica fora do MVP.
- **Aprovação:** proposta de experiência pronta para revisão; aprovação técnica e decisões de persistência pertencem ao Arquiteto/DBA. Nenhum código do aplicativo foi alterado pelo Designer.
- **Riscos abertos:** hipóteses de valor ainda não avaliadas com usuário; volume real desconhecido; limites de arquivo do DATA-HUB-01 são guardrails propostos, sujeitos à decisão técnica final; SVG não demonstra interação, responsividade real nem semântica de componentes de produção.
- **Validação realizada:** estudo da captura existente e leitura dos tokens/requisitos. Foram gerados artefatos, sem build, testes, navegador ou QA visual da implementação. A prancha é uma composição dos próprios SVG, não captura de aplicação funcional.
