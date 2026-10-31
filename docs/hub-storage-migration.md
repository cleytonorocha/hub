# Migração para armazenamento centralizado

O Compose atualizado usa o volume `hub_storage` em `/data`. O H2 abre `/data/database/hub`; capas ficam em `/data/users/<ownerId>/reading-library/covers`. O volume não é criado nem ativado pela ferramenta de migração. `hub_data` e `hub_media` devem permanecer disponíveis até a aceitação do cutover e do plano de rollback.

O volume Docker legado `hub_data` tem o banco H2 em `hub.mv.db` na raiz do volume (container antigo: `/data/hub.mv.db`); a cópia passa a `/data/database/hub.mv.db` dentro do volume novo. Localmente, o banco antigo fica em `%LOCALAPPDATA%\Hub\data\hub.mv.db` e vai para `%LOCALAPPDATA%\Hub\database\hub.mv.db`. Arquivos H2 irmãos, como `hub.trace.db`, são copiados mantendo o nome sob `database/`.

A cópia integral do H2 preserva os documentos de preferências e suas revisões junto com as demais tabelas; não restaure nem atualize esses campos separadamente.

## Snapshot e restauração antes de migrações de schema

O Hub executa migrações do H2 durante a inicialização. Antes de atualizar uma versão que altera tabelas ou chaves, pare todos os backends que usam o banco e faça um snapshot offline de **todos os arquivos** do diretório do banco, não apenas `hub.mv.db`. Inclua arquivos irmãos como `hub.trace.db`, se existirem. Não abra nem altere o banco de produção para criar ou testar o snapshot.

No Windows, ajuste os caminhos para a instalação e para um destino de backup separado. Execute somente depois que o backend estiver parado:

```powershell
$hubDbSource = Join-Path $env:LOCALAPPDATA 'Hub\database'
$hubDbSnapshot = 'D:\hub-snapshots\before-schema-update'
New-Item -ItemType Directory -Path $hubDbSnapshot -Force | Out-Null
Get-ChildItem -LiteralPath $hubDbSource -File | Copy-Item -Destination $hubDbSnapshot
$sourceHashes = Get-ChildItem -LiteralPath $hubDbSource -File | Get-FileHash -Algorithm SHA256
$snapshotHashes = Get-ChildItem -LiteralPath $hubDbSnapshot -File | Get-FileHash -Algorithm SHA256
Compare-Object ($sourceHashes | ForEach-Object { "$($_.Path | Split-Path -Leaf):$($_.Hash)" }) `
  ($snapshotHashes | ForEach-Object { "$($_.Path | Split-Path -Leaf):$($_.Hash)" })
```

Revise a saída de `Compare-Object`: nenhum item deve aparecer. Guarde o snapshot fora do diretório ativo e registre a versão do Hub e a data do backup. Em Docker, pare o backend e copie offline todos os arquivos de `/data/database/` para um diretório de backup independente, mantendo a estrutura e verificando hashes antes de iniciar a versão nova.

Se a inicialização falhar durante uma migração, primeiro consulte os logs e preserve o banco parcial para diagnóstico. A migração V3 é reiniciável depois que os dados de pré-validação foram corrigidos; para retornar ao schema anterior, pare o backend e restaure **o conjunto completo** de arquivos do snapshot correspondente à versão antiga. Mantenha uma cópia do estado parcial antes da restauração. Restaure para um diretório de recuperação, compare nomes e hashes com o snapshot e só então, com o backend parado, substitua a base ativa. Reinicie usando a versão compatível com o snapshot e confirme a inicialização pelos logs. Não misture arquivos de snapshots diferentes nem restaure enquanto qualquer processo Hub estiver conectado.

## Sessões de foco e recuperação

As sessões de foco ficam no banco H2 e pertencem ao usuário autenticado. Iniciar, pausar, retomar e encerrar gravam transições; o cronômetro calcula o tempo a partir desses instantes e não persiste atualizações a cada segundo. Pausas não contam como tempo focado. Após reabrir o Hub, a sessão ativa ou pausada é carregada para que a pessoa possa recuperar, pausar, retomar ou encerrar e conferir o tempo. Se o navegador ou o dispositivo ficou suspenso, confira e ajuste o tempo ao encerrar quando necessário; tempo decorrido não comprova atenção contínua.

O Hub não envia um alarme enquanto estiver fechado. Uma cópia portátil converte uma sessão ativa em uma fotografia pausada no instante da exportação; isso não altera a sessão original. Ao importar uma cópia, sessões abertas são restauradas pausadas e exigem uma ação explícita para retomar. Uma migração ou restauração de H2 deve preservar o banco completo para conservar o histórico e o marcador da sessão aberta.

## Etapas offline

1. Planeje uma janela e pare o backend por procedimento operacional. A ferramenta não inspeciona nem para containers. Não execute a migração enquanto houver qualquer processo Hub usando os volumes.
2. Faça backup verificado dos dois volumes antigos, incluindo todos os arquivos. Crie cópias offline consistentes em diretórios separados. O `--backup-root` deve conter `hub_data/` e `hub_media/`, cada um espelhando exatamente a respectiva origem.
3. Faça uma segunda cópia isolada dos arquivos do H2. Consulte somente essa cópia para exportar os vínculos de capas por owner. Um SQL de referência é:

   ```sql
   SELECT owner_id, cover_image_name, cover_image_sha256
   FROM hub_reading_item
   WHERE cover_image_name IS NOT NULL
   ORDER BY owner_id, cover_image_name;
   ```

   Grave o resultado em JSON com o formato `{ "owners": { "<UUID do owner>": { "<UUID.jpg ou UUID.png>": "<sha256>" } } }`. O hash deve corresponder ao arquivo JPEG/PNG completo. Uma exportação sem capas pode usar `{ "owners": {} }`. Não abra o H2 real em modo escrita para produzir o índice.
4. Execute primeiro sem `--apply`. O relatório lista arquivos planejados, hashes divergentes, referências sem arquivo, thumbnails ausentes e arquivos de capa órfãos. Capas e thumbnails vinculadas são copiadas para o caminho servido por owner. Arquivos sem vínculo são copiados para `quarantine/legacy-reading-covers/<sha256>/<nome>` no destino novo, fora dos diretórios servidos pela aplicação; o relatório registra nome de origem, hash, tamanho e destino. As cópias originais em `hub_media` permanecem intactas. A ferramenta verifica a cópia de backup byte a byte. Não prossiga enquanto houver `mismatches`.

   ```powershell
   python tools/migrate_storage.py `
     --source-data D:\hub-offline\hub_data `
     --source-media D:\hub-offline\hub_media `
     --backup-root E:\hub-backup-verified `
     --cover-index D:\hub-offline\cover-index.json `
     --destination F:\hub_storage-new `
     --report D:\hub-offline\migration-report.json `
     --backend-stopped --backup-verified
   ```

5. Revise o relatório e repita o mesmo comando com `--apply`. A aplicação grava somente no destino novo, valida SHA-256, usa arquivo temporário e é idempotente: conteúdo igual é aceito; conteúdo divergente interrompe a execução e aparece no relatório. Nenhum volume é removido e nenhum cutover é feito.
6. Arquivos de capa sem vínculo no índice ficam preservados nas origens e também são copiados para a quarentena não servida do destino novo. As cópias de quarentena são determinísticas, verificadas por SHA-256 e idempotentes; um conteúdo diferente no mesmo destino aparece como divergência. Retenha o relatório, a quarentena e as origens. O cutover Compose é uma operação posterior e separada, com aprovação operacional e rollback definidos.

A ferramenta requer `--backend-stopped` e `--backup-verified` mesmo no dry-run; são declarações explícitas do operador, não verificações remotas. Ela compara o backup com a cópia de origem e rejeita índices malformados, traversal e symlinks em entradas e destinos. Ela não consulta Docker, não toca volumes Docker diretamente, não modifica origens/backups e não atualiza Compose nem DNS. O harness offline pode ser executado com `python -m unittest tools.test_migrate_storage`; ele usa diretórios temporários e não acessa volumes ou containers.

Para o cutover Docker futuro, crie `hub_storage` somente depois de backup e janela autorizada. Execute o utilitário em um container auxiliar descartável com o novo volume montado no destino; assim, a execução escreve no volume nomeado, sem conectar ou parar o backend ativo. Adapte os caminhos offline ao host. O comando abaixo mostra o dry-run; acrescente `--apply` somente após revisar o relatório:

```powershell
docker volume create hub_storage
docker run --rm `
  --mount type=volume,source=hub_storage,target=/destination `
  --mount type=bind,source=D:/hub-offline,target=/input,readonly `
  --mount type=bind,source=E:/hub-backup-verified,target=/backup,readonly `
  --mount type=bind,source=$((Resolve-Path tools/migrate_storage.py).Path),target=/tool/migrate_storage.py,readonly `
  --mount type=bind,source=D:/hub-migration-reports,target=/report `
  python:3.12-alpine python /tool/migrate_storage.py `
    --source-data /input/hub_data --source-media /input/hub_media `
    --backup-root /backup --cover-index /input/cover-index.json `
    --destination /destination --report /report/migration-report.json `
    --backend-stopped --backup-verified
```

## Instalação local no Windows

O destino padrão local é `%LOCALAPPDATA%\Hub`; use `HUB_DATA_ROOT` para selecionar outra raiz. Para migrar a instalação anterior, pare o backend e copie `%LOCALAPPDATA%\Hub\data` e `%LOCALAPPDATA%\Hub\media` para diretórios offline separados, fora da raiz destino. Faça um espelho de backup independente e use esses diretórios offline como `--source-data`/`--source-media`, com `--destination %LOCALAPPDATA%\Hub`. Execute dry-run e aplique somente após o relatório ficar sem divergências. A ferramenta mantém os diretórios `data` e `media` antigos no lugar e cria `database`/`users` na raiz. Confirme o H2 na nova localização antes de remover qualquer dado legado por processo posterior autorizado.

## Rollback

Antes do cutover, rollback consiste em descartar o destino novo e seguir usando os volumes antigos intactos. Após um cutover aprovado, reverta a configuração para as imagens e volumes antigos mantendo `hub_storage` preservado para investigação. Não copie dados de volta automaticamente: depois que o novo Hub aceitar gravações, reconcilie-as por procedimento operacional antes de qualquer retorno ao estado anterior.
