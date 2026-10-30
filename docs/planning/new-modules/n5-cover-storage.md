# N5 — Imagens de capa e armazenamento local

## Escopo aprovado

- Cada item da Biblioteca de leitura pode ter uma imagem de capa opcional, enviada manualmente pelo detalhe.
- Aceitar JPEG e PNG com até 8 MiB; rejeitar imagens corrompidas, outros formatos e dimensões acima de 20 megapixels ou 12.000 px em qualquer lado.
- O servidor reencoda capas para remover metadados e limita a imagem principal a 1.600 px. A lista usa uma miniatura de até 420 px carregada sob demanda.
- Guardar nome aleatório interno e SHA-256 dos bytes canônicos como campos opcionais do item; ambos são alterados juntos pelo backend. Nunca usar o nome enviado pelo navegador como caminho.
- As capas ficam fora da raiz pública e do banco de dados. No Docker, um volume nomeado `hub_media` atende esta ferramenta; os arquivos ficam em `reading-library/covers`. Sem Docker, o caminho padrão fica em `%LOCALAPPDATA%\Hub\media\reading-library\covers`.

## Cópia portátil e recuperação

- Sem capas, a exportação mantém o JSON v4. Com capas, exporta ZIP v5: `manifest.json` e `assets/<id>.jpg|png`.
- Limites do ZIP: 100 MiB compactados e descompactados, manifesto até 5 MiB, 5.000 registros de leitura, 5.000 capas e 8 MiB por imagem. O manifesto liga cada imagem ao ID, formato, tamanho em bytes e SHA-256.
- Prévia não altera o acervo nem a pasta publicada. A confirmação reenvia e revalida pacote, imagens, hash e revisão. ID já existente com campos ou capa diferentes bloqueia a importação inteira. Importações idênticas são ignoradas; arquivos JSON v1–v4 continuam aceitos e não carregam capas.
- Uma cópia física consistente inclui banco e mídia no mesmo ponto no tempo. Pare o backend antes de copiar `%LOCALAPPDATA%\Hub\data` junto com `%LOCALAPPDATA%\Hub\media`, ou os volumes `hub_data` e `hub_media` do Compose.
- `docker compose down --volumes` remove ambos os volumes. Rollback de código mantém schema e arquivos; nunca apagar volume como rollback. Arquivos órfãos após interrupção são inofensivos e não são removidos automaticamente.

## Sequência de implantação

1. Parar o backend e obter cópia física consistente do banco e da mídia.
2. Iniciar a nova versão. O `schema.sql` adiciona a coluna nullable sem reescrever registros existentes; o novo volume é criado pelo Compose.
3. Validar upload, troca, remoção, round-trip ZIP v5 e importação de JSON antigo em instalação isolada antes de considerar o fluxo liberado.

## Evolução — progresso por páginas

- Livros podem informar `totalPages` (1–100.000) e `currentPage` (0–total). `0` significa ainda não iniciado; campos nulos significam que não há acompanhamento numérico.
- A página atual é editada no componente de progresso do detalhe. Cards mostram barra e `atual de total`; percentual é derivado e nunca altera o estado de leitura.
- Artigos e vídeos não recebem esses campos. `progressMarker` continua textual e independente; dados antigos não são inferidos a partir dele.
- A tabela recebe duas colunas nullable com constraints, sem backfill. Atualizações usam a revisão existente. Clientes antigos que omitem os campos preservam valores existentes.
- A cópia portátil exporta ZIP v6 sempre, mesmo sem capas, para preservar páginas. JSON v1–v4 e ZIP v5 continuam importáveis; itens antigos entram sem rastreio numérico. IDs locais com progresso diferente são conflito.
