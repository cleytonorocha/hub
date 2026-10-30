# Biblioteca de leitura — autores e capas por link

## Escopo aprovado

- Criar catálogo reutilizável de autores e associar zero ou vários autores somente a livros. “Cadastrar autor” abre modal; nomes duplicados após normalização são recusados. Autores em uso não são excluídos.
- Preservar `authorOrSource` para textos existentes e origem/editora de artigos ou vídeos; não inferir autores de registros antigos.
- Filtrar por autor no servidor sem duplicar itens ou contagens; manter paginação e ordenação atuais.
- Além do upload local, permitir colar um link de imagem do Google Books e importar a imagem sob ação explícita. O Hub armazena uma cópia validada no volume `hub_media`; não carrega hotlink remoto no navegador.
- Permitir HTTPS somente para `books.google.com`, subdomínios de `googleusercontent.com` e `gstatic.com`. Validar cada redirecionamento (máximo dois), DNS e endereço de destino; rejeitar credenciais, portas fora do padrão, endereços especiais/privados, arquivos acima de 8 MiB e conteúdo que não seja JPEG/PNG válido. Usar timeout; uma falha mantém a capa anterior.
- Atualizar a cópia portátil para ZIP v7 incluindo catálogo, vínculos e URL de origem. Continuar importando JSON v1–v4 e ZIP v5/v6 sem autores; conflitos não mesclam nem remapeiam IDs.
- O mockup usa “Diário de um homem supérfluo”, de Ivan Turguêniev. A usuária confirmou o cadastro no acervo com título e autor, sem capa nem páginas; não incluir seed automático no schema. Criar o registro pela interface depois do backup consistente e da migração segura.

## Revisões da Equipe Técnica

- **PO-HUB-N5-AUTHORS, nível 2:** catálogo reutilizável, coautoria, filtro paginado e cadastro inline; busca automática de metadados fora do MVP.
- **Designer:** fluxo aprovado, autor em destaque junto à capa, modal acessível e regras responsivas. Artefato: [N5-T4-authors.svg](screens/N5-T4-authors.svg).
- **ARCH-HUB-READING-AUTHORS-COVERLINK, nível 2:** aprovado com condições; importação por URL restrita à allowlist acima, DNS pinning, redirecionamentos controlados, armazenamento local e formato v7.
- **DBA-HUB-N5-AUTHORS, nível 2:** aprovado com condições; tabelas aditivas de autores/vínculos, índice reverso por autor, URL nullable da capa, transações e revisões existentes; aprovação exige bloquear resolução de DNS para faixas privadas/especiais e evitar DNS rebinding.

## Implantação e validação

- Migração aditiva: catálogo, associação e `cover_source_url`; sem backfill de `authorOrSource`. Rollback do código preserva schema e dados.
- Antes do primeiro startup que aplique o schema, fazer cópia consistente do H2 e do volume de mídia com o backend parado.
- Tester deve validar autores repetidos/normalização, coautoria, filtro paginado, preservação de legados, importação de capa e SSRF/redirecionamentos/timeout/limites, capa mantida em falha, ZIP v7 e compatibilidade v1–v6, teclado e mobile.
