# Downloads de vídeos

O Hub usa yt-dlp localmente para preparar um vídeo público individual do YouTube ou Instagram e o entrega pelo gerenciador de downloads do navegador. Não utiliza API de terceiros, Telegram, cookies das plataformas ou armazenamento permanente.

## Qualidade e formatos

A qualidade preferida pode ser 480p, 720p ou 1080p; o padrão é 720p. O extrator escolhe a melhor resolução disponível até a preferência. Se todas forem maiores, entrega uma resolução nativa disponível e a interface avisa. Vídeos verticais são classificados pela dimensão menor; a proporção e o áudio presente na origem são preservados.

Por padrão, o FFmpeg apenas reúne áudio e vídeo ou altera o contêiner sem recodificar os streams. A transcodificação opcional está descrita abaixo; não há aumento artificial da resolução. O formato efetivamente preparado é informado na tela e na extensão do arquivo; MP4 não é garantido para toda origem.

A tela oferece a opção **Reduzir o tamanho do arquivo**, desativada por padrão. Quando ativada, o FFmpeg recodifica o vídeo para H.264 (CRF 28) e o áudio presente para AAC 96 kbit/s, preservando resolução, proporção e orientação. A recodificação pode reduzir a qualidade visual e usa o limite de tempo da preparação. Se o resultado não ficar menor que o original, o Hub mantém o original. A opção não aumenta resolução nem promete um tamanho-alvo.

Playlists, perfis, coleções, transmissões ao vivo e conteúdo que exija autenticação não são aceitos. Um link reconhecido não garante que a plataforma permita a extração. Os extratores podem precisar de atualização quando as plataformas mudam.

## Executar e configurar

O caminho recomendado é o Docker Compose. A imagem inclui `yt-dlp[default]==2026.10.26` (com EJS compatível), Node 22.23.4 e FFmpeg/ffprobe. O runtime Node é habilitado explicitamente no comando yt-dlp. As ferramentas não são atualizadas durante requisições.

Para execução local, instale Python 3.11+, Node 22+, FFmpeg/ffprobe e o pacote:

```sh
python -m pip install "yt-dlp[default]==2026.10.26"
```

Os executáveis podem ser encontrados no `PATH` ou configurados pelas variáveis abaixo. Informe o caminho de um executável, sem acrescentar argumentos de shell.

| Variável | Padrão | Uso |
| --- | --- | --- |
| `HUB_VIDEO_DOWNLOAD_YT_DLP_COMMAND` | `yt-dlp` | Executável do yt-dlp |
| `HUB_VIDEO_DOWNLOAD_NODE_COMMAND` | `node` | Runtime JavaScript 22+ |
| `HUB_VIDEO_DOWNLOAD_FFMPEG_COMMAND` | `ffmpeg` | Merge/remux |
| `HUB_VIDEO_DOWNLOAD_FFPROBE_COMMAND` | `ffprobe` | Verificação da mídia final |
| `HUB_VIDEO_DOWNLOAD_TEMP_ROOT` | Diretório temporário Java + `hub-video-downloads` | Raiz exclusiva dos trabalhos temporários |

O Compose utiliza `/var/tmp/hub/video-downloads`, sem volume persistente, e um processo init mínimo para encaminhar sinais e recolher subprocessos órfãos. Para execução local, escolha uma raiz exclusiva para o downloader: ela é limpa no reinício e nunca deve apontar para pastas com outros arquivos. Se uma dependência estiver ausente, o Hub continua disponível e o downloader retorna uma mensagem de indisponibilidade.

## API

Todas as rotas exigem sessão autenticada, permissão `VIDEO_DOWNLOAD` e propriedade do trabalho. POST e DELETE também exigem o token CSRF obtido em `/api/auth/csrf`. Identificadores de trabalhos não concedem acesso a outra conta.

**Mudança de contrato:** `POST /api/video-downloads` passa de resposta binária síncrona para criação assíncrona com JSON e status `202`.

```json
{
  "platform": "YOUTUBE",
  "url": "https://www.youtube.com/watch?v=VIDEO_ID",
  "preferredQuality": 720,
  "reduceFileSize": false
}
```

`platform` aceita `YOUTUBE` ou `INSTAGRAM`; `preferredQuality` aceita 480, 720 ou 1080 e assume 720 quando omitido. `reduceFileSize` é opcional e assume `false`.

| Rota | Resposta |
| --- | --- |
| `POST /api/video-downloads` | `202`: identificador, estado inicial e URL de consulta |
| `GET /api/video-downloads/{id}` | Estado, metadados disponíveis, validade e eventual erro público |
| `GET /api/video-downloads/{id}/file` | Attachment do arquivo pronto, sem acumular o vídeo em um Blob da página |
| `DELETE /api/video-downloads/{id}` | Cancela o trabalho e remove seus temporários |

Os estados são `RUNNING`, `READY`, `TRANSFERRING`, `COMPLETED`, `FAILED`, `CANCELLED` e `EXPIRED`. A tela consulta o estado a cada dois segundos e inicia a entrega automaticamente quando o arquivo fica pronto. Durante `TRANSFERRING`, `bytesTransferred` informa os bytes aceitos pelo stream do servidor e a interface mostra o progresso em relação a `sizeBytes`. O arquivo é enviado em blocos, sem acumular o vídeo em memória; a porcentagem representa os bytes enviados pelo backend, não uma confirmação de gravação no dispositivo. “Download enviado ao navegador” não confirma que o usuário salvou o arquivo.

Trabalhos de outra conta retornam `404`. Entrada inválida retorna `400`; falta de sessão/permissão ou CSRF inválido retorna `401/403`; capacidade ocupada retorna `429`; dependências ou armazenamento indisponíveis retornam `503`. Falhas durante a preparação são informadas no estado do trabalho. Um arquivo expirado retorna `410` enquanto o registro temporário ainda existir; após sua remoção, retorna `404`.

## Limites e ciclo de vida

- Dois trabalhos globais, com um por usuário, incluindo arquivos prontos e transferências. Não há fila de espera.
- Arquivo final de até 1 GiB; preparação inteira de até dez minutos.
- Arquivo pronto disponível por dez minutos; transferência limitada a trinta minutos.
- Orçamento temporário de 3 GiB por trabalho e 6 GiB no total, mais margem operacional de 512 MiB. O monitoramento de uso pode ter pequena ultrapassagem entre amostras.
- Temporários removidos após sucesso, falha, cancelamento, desconexão ou expiração. Uma falha de limpeza conserva a reserva de recursos e é repetida pelo processo de manutenção.
- Reiniciar o backend descarta os trabalhos; o usuário deve preparar o vídeo novamente.

O diretório temporário deve ter espaço para os streams intermediários e para o merge. O limite final do arquivo não equivale ao consumo máximo durante a preparação. Nginx entrega os attachments sem buffering em disco e permite até trinta minutos de inatividade. O limite absoluto de trinta minutos de transferência é aplicado pelo backend.

## Operação e recuperação

Mantenha os bindings de interface e API em `127.0.0.1`, como no Compose. A validação de hosts, caminhos e DNS inicial reduz risco, mas não controla todas as conexões posteriores do extrator e das CDNs. Exposição pública exige controle de saída de rede para o processo e seus descendentes, bloqueando redes internas, loopback, link-local e endpoints de metadata.

Uma atualização do yt-dlp é feita alterando a versão fixada, reconstruindo a imagem e validando novamente os extratores. Para rollback, restaure as imagens anteriores; trabalhos temporários serão descartados. Esta entrega não altera schema nem dados permanentes.
