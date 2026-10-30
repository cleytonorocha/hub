# Novos módulos para o Hub — descoberta de produto

Handoff: **PO-HUB-02** · Papel: Product Owner · Nível 2 · Data: 28/10/2026.

## Correção do alcance e decisão

O levantamento anterior, **PO-HUB-01**, permanece em `docs/planning/po-features.md` como **melhorias do núcleo de tarefas e calendário**: busca global, cópia portátil, recorrência e checklist. Ele não esgota o potencial do Hub.

Este levantamento propõe **seis funcionalidades novas**, cada uma com um problema e um espaço próprios. A ambição proposta é evoluir de calendário de tarefas para um ambiente pessoal que reúna conhecimento, dinheiro, rotinas, atenção, conteúdos e documentos. Essa direção é uma hipótese de produto; não é demanda já validada nem autorização para implementar todos os módulos de uma vez.

O README foi lido como contexto do ponto de partida, não como limite de oportunidades. Foram considerados o propósito de organização pessoal, uso local e navegação modular. Não há pesquisa com usuários, dados de adoção, orçamento ou prazo que sustentem um ranking quantitativo. Estimativas abaixo são relativas. `/caveman-lite` não foi executado: comando correspondente indisponível nesta atuação.

**Decisão para esta rodada:** preparar seis briefings e **três telas conectadas por módulo, totalizando 18 telas**, para exploração pelo Designer. As telas serão propostas, não funcionalidades prontas. Arquiteto/DBA devem revisar viabilidade e proteção dos dados antes de uma implementação futura. Não executar builds, testes, QA de navegador nem alteração do aplicativo nesta descoberta.

## Lista priorizada

| Ordem | Módulo novo | Valor principal | Esforço relativo | Confiança na hipótese | Dependência externa no MVP | Sensibilidade |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | N1 — Notas e conhecimento | Guardar ideias e contexto sem depender de uma tarefa | Médio | Média | Nenhuma | Texto pessoal potencialmente sensível |
| 2 | N2 — Finanças pessoais manuais | Entender receitas, despesas e resultado do mês | Médio a alto | Média | Nenhuma; lançamento manual | Alta: informações financeiras |
| 3 | N3 — Hábitos e rotinas | Acompanhar constância sem transformar tudo em prazo | Médio | Média | Nenhuma | Rotinas podem revelar informações pessoais/saúde |
| 4 | N4 — Sessões de foco | Reservar atenção e registrar esforço real | Médio | Média a baixa | Nenhuma; não depende de alarmes do sistema | Histórico de atividade pessoal |
| 5 | N5 — Biblioteca de leitura e conteúdos | Organizar o que quer ler, assistir ou estudar | Médio | Média a baixa | Links opcionais, sem buscar conteúdo automaticamente | Interesses e histórico de consumo |
| 6 | N6 — Documentos pessoais e vencimentos | Localizar documentos e preparar renovações | Alto | Média a baixa | Nenhuma no cadastro; anexos locais opcionais | Alta: documentos e anexos pessoais |

**Por que essa ordem:** N1 amplia imediatamente a utilidade do Hub com baixa dependência externa; N2 abre uma área de valor distinta, porém exige mais cuidado e regras; N3 acrescenta acompanhamento contínuo com resultado verificável. N4 e N5 são úteis, mas devem provar que a pessoa quer centralizá-los aqui. N6 é valioso, porém tem maior custo de proteção e recuperação. A ordem não equivale a compromisso de construção; a shortlist mais forte para a primeira rodada de validação é **N1, N2 e N3**.

O esforço inclui a experiência, os estados de erro e a preservação/recuperação dos novos dados. Não se presume que a cópia portátil de tarefas prevista em PO-HUB-01 já cubra notas, dinheiro, hábitos, sessões, conteúdos ou arquivos.

## N1 — Notas e conhecimento

**Delivery slice:** criar e recuperar notas independentes, com organização leve e conexão opcional com uma tarefa existente.

**Problema / usuário:** pessoa registra ideias, decisões, instruções e referências em lugares separados; não quer inventar uma data ou uma tarefa para guardar contexto.

**Valor e conexão ao Hub:** oferece memória pessoal consultável. Uma nota pode referenciar uma tarefa; o texto continua existindo mesmo quando o trabalho associado terminou. Não duplicar as notas curtas de uma tarefa como se fossem um novo módulo: a nota aqui tem título, corpo e ciclo próprio.

**MVP dentro:** criar, editar, buscar por título/corpo, atribuir etiquetas, fixar, arquivar e abrir notas; link opcional para uma tarefa selecionada pelo usuário; salvamento explícito com feedback de pendências.

**Fora:** colaboração, editor de blocos avançado, anexos, IA, captura automática da web, sincronização, grafo de conhecimento e busca semântica.

**Regras e bordas:** título obrigatório; corpo pode começar vazio; arquivar não exclui e tira a nota da visão padrão; exibir data de alteração. Fechar com alterações não salvas pede descartar ou continuar. Falha preserva o texto no contexto atual e não anuncia sucesso. Uma tarefa excluída não remove a nota; vínculo deve aparecer como indisponível e poder ser removido. Etiqueta é texto, não pasta obrigatória. Exclusão de nota exige confirmação específica.

**Três telas conectadas:**

1. **N1-T1 — Biblioteca de notas:** lista/cartões, busca, etiquetas, fixadas/arquivadas e ação “Nova nota”; estado vazio com convite claro.
2. **N1-T2 — Editor de nota:** título, corpo, etiquetas, salvar e indicação de alteração não salva; escolha opcional de tarefa relacionada.
3. **N1-T3 — Nota e contexto:** conteúdo salvo, etiquetas, links e tarefa relacionada; editar/arquivar e retorno à biblioteca preservando busca.

**Acceptance criteria:** criar uma nota sem tarefa ou data e reabrir com o mesmo conteúdo; busca ignora caixa/acentos; arquivar e desarquivar preserva tudo; erro não descarta texto nem informa salvamento; excluir tarefa relacionada mantém a nota; sair com edição pendente exige escolha explícita.

**Métrica de descoberta:** capacidade de reencontrar uma nota e identificar o que está salvo em sessão de usabilidade. Sem baseline; não coletar o conteúdo por telemetria.

**Riscos/dependências:** recuperação dos textos precisa entrar no escopo de dados; conteúdo pode conter segredos que não devem ser enviado a terceiros por padrão. Não prometer versionamento/histórico de revisões no MVP. Esforço médio, confiança média e sem dependência externa obrigatória.

## N2 — Finanças pessoais manuais

**Delivery slice:** registrar receitas/despesas em reais e consultar resultado e distribuição de gastos por mês.

**Problema / usuário:** pessoa quer saber para onde o dinheiro foi e o resultado do período sem conectar contas bancárias nem usar planilha.

**Valor e conexão ao Hub:** acrescenta visão financeira pessoal independente das tarefas. Uma despesa pode motivar uma tarefa criada conscientemente, como revisar um gasto; lançamento e tarefa mantêm identidades e conclusões próprias. Não exigir essa associação para obter valor.

**MVP dentro:** lançamentos manuais de receita/despesa com descrição, valor positivo, data e categoria; resumo mensal, extrato filtrado, editar/excluir com confirmação e comparação por categorias dentro do mesmo mês. Moeda única **BRL** e até duas casas decimais.

**Fora:** conexão bancária, pagamento, investimentos, recomendação financeira, múltiplas moedas/contas, transferência, saldo bancário, parcelamento automático, previsão, recorrência automática e contabilidade fiscal.

**Regras e bordas:** receita/despesa define o sinal do efeito; entrada do valor é sempre positiva e maior que zero, sem arredondar silenciosamente dígitos adicionais. Total de receitas menos total de despesas é **resultado do mês**, não “saldo da conta”. Datas determinam o mês; corrigir a data move o efeito entre períodos. Valor não pode virar texto inválido ou sofrer dupla inversão de sinal. Duas despesas iguais podem ser legítimas: não deduplicar automaticamente. Salvar em andamento impede duplo envio da mesma ação na tela. Excluir e editar devem atualizar os mesmos totais usados no extrato; falha mantém o registro confirmado anterior. Sem dados é estado vazio, não prova de renda/gasto zero conhecido.

**Três telas conectadas:**

1. **N2-T1 — Resumo financeiro mensal:** mês selecionado, receitas, despesas, resultado e distribuição textual por categoria; sinalizar valores ilustrativos no mockup.
2. **N2-T2 — Novo lançamento:** receita/despesa, descrição, valor em R$, data e categoria, com resumo do efeito antes de salvar e validação localizada.
3. **N2-T3 — Extrato e revisão:** filtros por tipo/categoria, lista com total coerente, detalhe/edição/exclusão de lançamento e retorno ao mês correspondente.

**Acceptance criteria:** receita R$ 1.000,00 e despesa R$ 125,50 no mesmo mês produzem receitas R$ 1.000,00, despesas R$ 125,50 e resultado R$ 874,50; editar despesa para R$ 150,00 muda resultado para R$ 850,00; mover para outro mês corrige ambos; zero/negativo/texto e precisão extra são rejeitados com mensagem; cancelar exclusão preserva os totais; a interface nunca rotula o resultado como saldo bancário.

**Métrica de descoberta:** pessoa consegue registrar um gasto e explicar o resultado mensal sem ajuda; discrepância entre totais exibidos e lançamentos deve ser zero nos cenários de aceite. Sem promessa de economia real ou baseline de gastos.

**Riscos/dependências:** informação financeira é sensível; definir acesso e recuperação antes de liberação, preservar precisão e explicar limites de interpretação. Sem integração externa ou credenciais de banco. Esforço médio a alto; confiança média no valor potencial, sem demanda comprovada.

## N3 — Hábitos e rotinas

**Delivery slice:** definir hábitos por dias da semana e registrar a execução de cada dia elegível.

**Problema / usuário:** pessoa quer perceber constância em leitura, estudo ou movimento; marcar uma tarefa vencida não descreve bem uma prática repetida.

**Valor e conexão ao Hub:** traz acompanhamento de comportamento, distinto de compromisso com prazo. Pode ser apresentado ao lado do dia no Hub, mas marcar hábito não conclui tarefa e não cria tarefas automaticamente.

**MVP dentro:** hábito com nome, dias da semana e data de início; painel de hoje; marcar/desmarcar execução em hoje ou dia passado elegível; histórico semanal e taxa de execução na semana; pausar e retomar.

**Fora:** recomendações de saúde, metas clínicas, punições, ranking social, recompensas complexas, contagem quantitativa (litros/km), lembrete garantido fora do app e geração automática de tarefas.

**Regras e bordas:** dia é o dia local do usuário; só dias previstos desde o início e não pausados entram no denominador. Hoje não marcado é “ainda não registrado”; dia passado elegível sem marca é “não registrado”, sem diagnóstico de falha pessoal. Futuro não pode ser registrado como executado. Pausar vale a partir de hoje e preserva todos os registros já feitos, sem apagar histórico; período pausado não conta como oportunidade perdida. Retomar volta a prever dias a partir da retomada. Alterar dias da semana afeta somente hoje ainda não registrado e futuro, preservando a definição histórica. MVP não exibe sequência/streak se não houver regra inequívoca; mostrar X de Y registros elegíveis. Semana sem dia elegível mostra “sem dias previstos”, não 0%.

**Três telas conectadas:**

1. **N3-T1 — Hábitos de hoje:** previstos para o dia, marcar/desmarcar e acesso ao histórico; estado “sem hábitos hoje” distinto de nenhum cadastro.
2. **N3-T2 — Configurar hábito:** nome, dias da semana, início e resumo em linguagem natural; fluxo de editar/pausar como estado desta tela.
3. **N3-T3 — Histórico semanal:** grade textual de dias, registros/denominador, períodos pausados e ação para voltar ao hábito.

**Acceptance criteria:** hábito segunda/quarta/sexta só aparece nesses dias desde o início; marcar duas vezes o mesmo dia não duplica execução; desmarcar corrige X de Y; futuro é bloqueado; pausa preserva o passado e exclui dias pausados do denominador; mudar frequência não reescreve o histórico; semana vazia tem mensagem própria.

**Métrica de descoberta:** compreensão dos dias previstos e do X de Y, e retorno voluntário para registrar em diferentes dias. Sem baseline, afirmação de melhora de saúde ou garantia de formação de hábito.

**Riscos/dependências:** datas locais e mudanças de frequência precisam ser inequívocas; dados podem revelar saúde/rotina. Evitar linguagem de culpa. Não depender da recorrência de tarefas F3 para entregar esta proposta. Esforço médio, confiança média, sem integração externa obrigatória.

## N4 — Sessões de foco

**Delivery slice:** iniciar uma sessão com objetivo e duração, pausar/retomar e registrar como terminou.

**Problema / usuário:** pessoa quer reservar atenção e perceber quanto tempo dedicou a uma atividade; calendário mostra intenção, não o esforço realizado.

**Valor e conexão ao Hub:** acompanha execução temporal sem impor uma metodologia de produtividade; associação a tarefa é opcional e não muda automaticamente sua conclusão.

**MVP dentro:** objetivo da sessão, duração planejada positiva, iniciar/pausar/retomar/encerrar, uma sessão ativa por vez e histórico com planejado versus tempo em foco registrado; vínculo opcional com tarefa. Não fixar presets como único modo de entrada.

**Fora:** bloquear sites/aplicativos, monitorar outras janelas, captura de tela, Pomodoro automático com ciclos/pausas, métricas de desempenho pessoal e alarmes garantidos com app fechado.

**Regras e bordas:** pausa não conta como foco; duração atingida solicita encerramento, sem iniciar outra sessão sozinha. Encerramento antecipado fica identificado como “encerrada antes do planejado”, sem ser apagado ou chamado de concluído integralmente. Reabrir a página oferece recuperar ou encerrar uma sessão em andamento; nunca criar uma segunda silenciosamente. Se a aba ficou suspensa, mostrar que o registro de tempo precisa ser conferido e permitir encerrar com duração revisada, identificada como ajustada. Nenhuma afirmação de que a pessoa trabalhou continuamente apenas porque o relógio avançou. Erro ao salvar mantém resumo para tentar novamente. Vínculo com tarefa excluída não apaga sessão.

**Três telas conectadas:**

1. **N4-T1 — Preparar foco:** objetivo, duração e tarefa opcional; mostrar sessão ativa recuperável quando existir.
2. **N4-T2 — Sessão em andamento:** objetivo, cronômetro, estado ativo/pausado, pausar/retomar e encerrar; aviso de limitações em suspensão/fechamento.
3. **N4-T3 — Resumo e histórico:** duração planejada/registrada, encerramento normal/antecipado/ajustado, anotação opcional e sessões anteriores.

**Acceptance criteria:** pausas não entram na duração de foco; iniciar outra sessão durante uma ativa exige resolver a anterior; encerramento antecipado aparece corretamente; reabrir oferece recuperação; concluir sessão não conclui tarefa; falha de gravação não anuncia sucesso nem duplica sessão numa nova tentativa.

**Métrica de descoberta:** pessoa entende o estado da sessão e registra o encerramento sem confundir tempo decorrido com foco real. Sem metas de produtividade presumidas.

**Riscos/dependências:** comportamento de tempo/suspensão e recuperação merece revisão técnica; não prometer execução em segundo plano independente do navegador. Esforço médio e confiança média a baixa; sem integração externa.

## N5 — Biblioteca de leitura e conteúdos

**Delivery slice:** guardar livros, artigos e vídeos numa fila pessoal e acompanhar o que começou/concluiu com notas de aprendizado.

**Problema / usuário:** pessoa acumula links e recomendações, mas não sabe o que pretende consumir nem onde parou.

**Valor e conexão ao Hub:** organiza repertório, distinto de uma lista de tarefas; uma sessão de leitura pode virar tarefa mediante ação explícita, sem converter automaticamente todo conteúdo em obrigação.

**MVP dentro:** cadastro manual com título, tipo (livro/artigo/vídeo), autor ou origem opcional, link opcional, estado “A ler”, “Em andamento”, “Concluído”; marcador textual de onde parou, etiquetas e nota pessoal.

**Fora:** buscar metadados automaticamente, baixar conteúdo, importar histórico de plataformas, player/leitor embutido, DRM, recomendação por IA e sincronização com serviços de leitura.

**Regras e bordas:** título e tipo obrigatórios, link não obrigatório (livro físico deve funcionar). Marcador pode ser “capítulo 3” ou “12:30”; não mostrar percentual sem denominador conhecido. Estado de consumo é independente de tarefa vinculada. Abrir link externo é ação explícita, aceitando apenas endereços web válidos e deixando a saída do Hub clara; cadastro não precisa acessar a rede. Se o link ficar indisponível, nota e registro continuam úteis. Títulos repetidos podem ser edições diferentes e não são deduplicados silenciosamente. Arquivar tira da fila sem apagar anotações.

**Três telas conectadas:**

1. **N5-T1 — Minha fila de conteúdos:** lista por estado/tipo, busca simples e entrada para adicionar.
2. **N5-T2 — Cadastrar conteúdo:** título, tipo, autor/origem e link opcional, com exemplo de livro sem link.
3. **N5-T3 — Progresso e aprendizado:** alterar estado, registrar marcador e nota, abrir link conscientemente e arquivar.

**Acceptance criteria:** livro sem link pode ser cadastrado e concluído; artigo com link mantém seus dados sem acesso automático à rede; alterar estado e marcador persiste após reabrir; URL inválida é rejeitada sem perder entrada; concluir conteúdo não conclui tarefa; arquivar preserva notas.

**Métrica de descoberta:** pessoa reencontra uma recomendação e registra onde parou. Sem promessa de aumentar leitura nem contagem de consumo baseada em cliques.

**Riscos/dependências:** anotações e interesses são dados pessoais; recuperar esses dados é parte da entrega. Não confundir com downloader existente. Esforço médio, confiança média a baixa, integração externa não obrigatória.

## N6 — Documentos pessoais e vencimentos

**Delivery slice:** organizar referências de documentos pessoais, anexar uma cópia opcional e acompanhar validade para preparar renovação.

**Problema / usuário:** pessoa não encontra um documento quando precisa ou lembra tarde que sua validade está acabando.

**Valor e conexão ao Hub:** cria catálogo documental com contexto de validade; ação “Criar tarefa de renovação” preenche uma tarefa após revisão consciente, sem renovar documento ou trocar arquivos automaticamente.

**MVP dentro:** título, categoria, observação, data de validade opcional e um anexo opcional PDF/JPG/PNG; catálogo com busca/estado de validade; consultar e remover anexo; criar tarefa de renovação com data escolhida.

**Fora:** senha/cofre de senhas, número completo de cartão, OCR, assinatura digital, protocolo de renovação, compartilhamento público, upload em nuvem, versionamento e alegação de criptografia/cofre sem implementação e verificação correspondentes. Não exigir dados identificadores como CPF para catalogar um documento.

**Regras e bordas:** funcionar apenas com metadados se pessoa não quiser anexar cópia. Validade é data local: anterior a hoje = vencido, hoje = vence hoje, posterior = válido, ausente = sem validade informada; não inferir vencimento. Excluir catálogo e excluir anexo são ações explícitas com confirmação e efeito descrito. Criar tarefa de renovação preserva documento e não altera validade nem marca renovação concluída. Documento com tarefa relacionada mostra a ligação para evitar criação acidental repetida, mas permite nova tarefa mediante escolha. Tipo/tamanho de arquivo precisam ser verificados; limite final cabe à revisão técnica e deve estar visível antes da seleção. Arquivo inválido não apaga metadados ou anexo anterior. Dados demonstrativos dos mockups não devem usar documentos reais do usuário.

**Três telas conectadas:**

1. **N6-T1 — Meus documentos:** catálogo por categoria, validade e busca, com estado vazio e registros sem anexo.
2. **N6-T2 — Registrar documento:** título, categoria, validade e notas; anexo opcional com formatos/limite e feedback de erro.
3. **N6-T3 — Documento e renovação:** metadados, arquivo disponível/ausente, vencimento, ação para revisar criação de tarefa e remoção confirmada.

**Acceptance criteria:** cadastrar sem anexo/validade funciona e exibe estados honestos; datas ontem/hoje/amanhã geram rótulos corretos; anexo inválido não remove anterior; criar tarefa requer revisão e não muda documento; cancelar exclusão mantém dados; vínculo de renovação fica visível; cópia e recuperação devem preservar tanto metadados quanto anexos antes da liberação.

**Métrica de descoberta:** pessoa localiza um documento e consegue preparar uma renovação sem interpretar a tarefa criada como renovação já realizada. Sem baseline ou garantia de evitar vencimentos.

**Riscos/dependências:** alto potencial de exposição e perda de arquivos pessoais. Precisa de decisão explícita sobre acesso, armazenamento, recuperação e remoção antes de implementação; oferecer catálogo sem anexos é um primeiro corte válido caso os requisitos de proteção ainda não estejam fechados. Esforço alto, confiança média a baixa, sem integração externa obrigatória.

## Cenários principais para o Tester — propostas, não executadas

| Scenario ID | Priority | Preconditions | Actor/permission | Steps | Expected result | Error/negative state | Browser-visible assertion | Regression scope |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N1-01 | P1 | Biblioteca vazia e depois tarefa vinculada | Usuário local | Criar nota sem data; salvar; buscar; editar; cancelar saída; arquivar; excluir tarefa associada | Texto e nota independentes preservados | Falha ao salvar/vínculo indisponível | Estado de salvamento e vínculo honestos | Notas dentro de tarefas continuam independentes |
| N2-01 | P1 | Mês sem lançamentos | Usuário local | Lançar receita 1.000,00 e despesa 125,50; editar; mudar mês; cancelar exclusão | Totais exatamente coerentes com extrato | Zero/negativo/texto/precisão extra; falha ao salvar | Resultado 874,50 inicialmente; não rotular como saldo | Tarefas e calendário não recebem efeito financeiro implícito |
| N3-01 | P1 | Hábito seg/qua/sex com início conhecido | Usuário local | Marcar/desmarcar; tentar futuro; pausar; retomar; alterar frequência | Histórico preservado e denominador elegível correto | Semana sem dias e falha de registro | “Sem dias previstos” e estados distintos | Sem tarefas recorrentes criadas implicitamente |
| N4-01 | P1 | Nenhuma sessão e depois sessão ativa | Usuário local | Iniciar; pausar; retomar; reabrir; encerrar antecipadamente | Uma sessão, duração sem pausas, encerramento claro | Suspensão e falha de gravação | Recuperação e estado ajustado quando pertinente | Conclusão de tarefa permanece independente |
| N5-01 | P2 | Livro físico e artigo com URL | Usuário local | Cadastrar ambos; marcar progresso; reabrir; arquivar | Marcador/notas persistem sem link obrigatório | URL inválida e rede indisponível | Sem percentual inventado ou download automático | Ferramenta de download permanece separada |
| N6-01 | P1 | Catálogo com validade ontem/hoje/amanhã e anexo anterior | Usuário local | Cadastrar sem arquivo; tentar inválido; preparar tarefa; cancelar exclusão | Datas e vínculos corretos; nenhum apagamento indevido | Tipo/tamanho inválido e falha de gravação | Escopo de exclusão e revisão da tarefa explícitos | Documento não muda ao concluir tarefa |

Cada feature deve incluir estados vazio, carregando, erro, edição não salva e confirmação sensível onde aplicável. Cancelar não pode gravar; erro não pode parecer coleção vazia; sucesso precisa refletir uma operação realmente concluída. Novos registros precisam ser recuperáveis antes de sua liberação, sem prometer que o backup atual de tarefas já os contém.

## Handoff ao Designer e sequência

1. **PO entrega os seis problemas e recortes acima.** Avaliar interesse e rotina real do usuário antes de tratar estimativa como prioridade definitiva.
2. **Designer explora as 18 telas indicadas**, preservando os seis fluxos e anotando estados alternativos dentro das telas; os nomes podem ser refinados sem acrescentar escopo implícito. Mockups devem usar dados fictícios identificados como demonstração.
3. **Reconciliação PO/Designer:** verificar se cada módulo tem valor próprio, se campos e ações correspondem ao MVP e se conteúdo não promete capacidades ausentes. Não transformar toda ideia em tarefa com data obrigatória.
4. **Selecionar um primeiro módulo para futura entrega.** Recomendação: validar N1; depois N2 e N3 segundo interesse declarado, sensibilidade e custo. As seis propostas são opções de evolução, não um grande release único.
5. **Arquiteto/DBA revisam o recorte selecionado**, incluindo integridade, acesso local, recuperação e arquivos quando houver. PO não decide contratos técnicos ou tecnologia nesta descoberta.
6. **Executor/Tester entram na fase posterior**, sob aprovações pertinentes; critérios acima são especificações de resultado, não evidência de teste executado.

## Registro final do PO

- **Decisão:** acrescentar seis novos módulos candidatos N1–N6; manter F1–F4 como melhorias do núcleo em documento separado.
- **Hipóteses:** uso individual, interesse em centralizar áreas pessoais e preferência potencial por operação local. Essas hipóteses exigem conversa/observação; não se presume demanda ou migração de ferramentas.
- **Invariantes de produto:** organização de tarefas continua útil isoladamente; módulos não concluem ou alteram registros uns dos outros sem ação explícita; informação financeira e documental não é enviada a terceiros por padrão; relações não causam exclusão em cascata implícita.
- **Risco de portfólio:** seis áreas podem produzir navegação excessiva e manutenção alta. Validar e entregar um módulo por vez; escolher outros módulos depois de comprovar utilidade.
- **Aprovação:** pronto para descoberta visual e análise técnica; implementação não executada nem validada. Este artefato não é aceite de segurança, armazenamento ou testes.
