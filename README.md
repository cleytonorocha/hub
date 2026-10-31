# Hub

Aplicação web para organizar tarefas pessoais em um calendário, acompanhar prazos e personalizar a forma de visualizar cada período.

> **Status:** tarefas locais, calendário, biblioteca de leitura, downloads de vídeos e integração Google Tasks estão disponíveis. Google Tasks requer credenciais OAuth e uma chave de instalação fora do Git.

## Visão geral

O Hub combina uma interface Vue com uma API Spring Boot. As tasks locais são salvas no banco H2 e podem ser criadas sem conexão com serviços externos.

### Calendário

Alterne entre as visualizações diária, semanal, mensal e anual. Cada uma oferece apresentações próprias, e as configurações permitem escolher qual apresentação abrir por padrão. Clique em uma data ou horário para iniciar uma task com esses dados preenchidos.

Capturas atuais da aplicação no navegador (30/10/2026):

| Dia | Semana |
| --- | --- |
| [![Calendário diário](docs/screenshots/calendar-day.png)](docs/screenshots/calendar-day.png) | [![Calendário semanal](docs/screenshots/calendar-week.png)](docs/screenshots/calendar-week.png) |

| Mês | Ano |
| --- | --- |
| [![Calendário mensal](docs/screenshots/calendar-month.png)](docs/screenshots/calendar-month.png) | [![Calendário anual](docs/screenshots/calendar-year.png)](docs/screenshots/calendar-year.png) |

Mockups de referência (não são capturas do app):

| Dia | Semana |
| --- | --- |
| ![Mockup de referência do calendário diário](front/design-reference/calendar/calendar-day.png) | ![Mockup de referência do calendário semanal](front/design-reference/calendar/calendar-week.png) |

| Mês | Ano |
| --- | --- |
| ![Mockup de referência do calendário mensal](front/design-reference/calendar/calendar-month.png) | ![Mockup de referência do calendário anual](front/design-reference/calendar/calendar-year.png) |

### Outras telas

Capturas atuais da aplicação no navegador (30/10/2026):

| Visão Geral | Notificações |
| --- | --- |
| [![Visão Geral](docs/screenshots/overview.png)](docs/screenshots/overview.png) | [![Notificações](docs/screenshots/notifications.png)](docs/screenshots/notifications.png) |

| Configurações | Ferramentas |
| --- | --- |
| [![Configurações](docs/screenshots/settings.png)](docs/screenshots/settings.png) | [![Ferramentas para baixar vídeos](docs/screenshots/tools.png)](docs/screenshots/tools.png) |

| Biblioteca de leitura |
| --- |
| [![Biblioteca de leitura](docs/screenshots/reading-library.png)](docs/screenshots/reading-library.png) |

### Recursos

- **Tasks locais:** título, data, horário, categoria, cor, prioridade, observações e estado de conclusão.
- **Busca global:** no Calendário, encontre tasks por título, categoria ou observações e filtre por estado, prioridade e período, incluindo tasks sem data ou concluídas.
- **Biblioteca de leitura:** organize livros, artigos e vídeos em fileiras por estado, na ordem **Em andamento**, **A ler** e **Concluído**. Cada fileira aparece somente quando há resultados. A busca aceita título, autor/origem e notas, com filtros por estado, tipo e autor e paginação no servidor. Livros podem ter capa local, etiquetas, notas e progresso por páginas; capas entram na cópia portátil.
- **Notificações:** visão de tasks vencidas, para hoje e para amanhã.
- **Google Tasks:** cada conta Hub pode conectar sua própria conta Google, escolher listas, sincronizar e editar tarefas e configurar avisos. O conteúdo de outra conta não fica disponível para administradores.
- **Configurações:** resumo semanal, valores padrão para novas tasks e apresentação padrão de cada visualização do calendário.
- **Baixar vídeos:** prepare um vídeo público individual do YouTube ou Instagram e receba o arquivo pelo gerenciador de downloads do navegador. Escolha 480p, 720p ou 1080p como qualidade preferida; quando só houver uma resolução maior, o Hub avisa e entrega uma versão original disponível, sem converter. O acesso depende da permissão de baixar vídeos.
- **Layout adaptável:** navegação lateral que pode ser recolhida e interface ajustada para telas menores.

### Contas e permissões

Na primeira abertura, a instalação apresenta um assistente local para criar a conta Administrador. O assistente encerra após a criação e atribui a essa conta os registros e capas que já existiam. Não há usuário nem senha padrão. Depois, o Administrador cria contas e perfis nomeados com permissões para tarefas, biblioteca, configurações e ferramentas. Os registros pessoais e as cópias portáteis pertencem à conta autenticada.

O login usa sessão do servidor, cookie HttpOnly, proteção CSRF e limite de tentativas. O Compose publica a interface e a API somente em `127.0.0.1`; o backend executado fora do Docker também usa bind local. **O primeiro setup sem token só está aprovado para esse acesso local. Não publique o app em LAN ou na internet**; uma implantação remota precisa desativar o setup aberto e definir um mecanismo administrativo de bootstrap seguro antes de expor a aplicação.

Os dados legados sem proprietário são associados ao primeiro Administrador. A conexão Google antiga `primary` permanece nessa conta. Cada nova conta Hub mantém seu próprio estado e credenciais do Google Tasks.

## Tecnologias

| Camada | Tecnologias |
| --- | --- |
| Frontend | Vue 3, Vue Router, Vite 8 |
| Backend | Java 25, Spring Boot 4, Spring Data JPA |
| Dados | H2 em arquivo |
| Containers | Docker Compose, Nginx |

## Requisitos

- Node.js 22.12 ou superior
- Java 25
- Para downloads fora do Docker: Python 3.11+, `yt-dlp[default]==2026.10.26`, FFmpeg/ffprobe e Node.js 22 ou superior no `PATH`
- Docker Desktop ou Docker Engine com Docker Compose v2, para executar em containers

O repositório inclui Maven Wrapper, então não é necessário instalar Maven separadamente.

## Executar localmente

Abra dois terminais na raiz do projeto.

**Backend** — Windows:

```powershell
cd back
.\mvnw.cmd spring-boot:run
```

Linux ou macOS:

```sh
cd back
./mvnw spring-boot:run
```

**Frontend**:

```sh
cd front
npm ci
npm run dev
```

Abra <http://localhost:8081>. O Vite encaminha as chamadas `/api` para o backend em `127.0.0.1:8080`.

O Docker instala as dependências de download, incluindo yt-dlp 2026.10.26, EJS compatível, Node 22.23.4 e FFmpeg/ffprobe. Fora do Docker, instale essas ferramentas ou configure seus caminhos. A ausência de uma delas deixa apenas o downloader indisponível. Consulte [configuração, API e limites do downloader](docs/video-downloads.md).

Para habilitar Google Tasks no Windows, configure `HUB_GOOGLE_OAUTH_CLIENT_ID`, `HUB_GOOGLE_CLIENT_SECRET_FILE` e `HUB_GOOGLE_CREDENTIAL_KEY_FILE` no ambiente do backend. O arquivo do segredo OAuth deve ser texto; a chave deve conter exatamente 32 bytes. Mantenha ambos fora do repositório. Registre no Google Cloud o URI de redirecionamento exato `http://localhost:8081/api/google-tasks/oauth/callback`.

## Executar com Docker Compose

Na raiz do projeto:

```sh
docker compose up --build -d
```

Abra <http://localhost:8081>. O front é compilado e servido pelo Nginx, que encaminha `/api` ao back. A API também é publicada em <http://localhost:8080>. Os containers possuem healthchecks, e o front aguarda a API ficar pronta.

Para personalizar as portas, copie `.env.example` para `.env` e altere `FRONTEND_PORT` ou `BACKEND_PORT`. Por padrão, as portas são publicadas somente em `127.0.0.1`.

Para habilitar Google Tasks no Compose, configure `HUB_GOOGLE_OAUTH_CLIENT_ID` e os caminhos locais de `HUB_GOOGLE_CLIENT_SECRET_FILE` e `HUB_GOOGLE_CREDENTIAL_KEY_FILE` no `.env`. Crie os dois arquivos fora do Git; a chave deve conter exatamente 32 bytes. Suba a aplicação com o overlay de secrets, montados somente para leitura:

```sh
docker compose -f compose.yaml -f compose.google.yaml up --build -d
```

Registre no Google Cloud o URI de redirecionamento exato `http://localhost:8081/api/google-tasks/oauth/callback`. Sem os três segredos válidos, a integração permanece desabilitada.

```sh
docker compose ps
docker compose logs -f
docker compose down
```

`docker compose down` mantém o volume `hub_storage`. O comando `docker compose down --volumes` apaga o banco e as imagens armazenadas.

## Dados e privacidade

- Sem Docker, os dados ficam sob `%LOCALAPPDATA%\Hub`: H2 em `database\hub` e capas em `users\<ownerId>\reading-library\covers`.
- Com Docker, o volume nomeado `hub_storage` monta em `/data`, com H2 em `/data/database/hub` e arquivos de cada usuário em `/data/users/<ownerId>`.
- Preferências de interface são armazenadas por usuário na API. Valores antigos em `localStorage` namespaced por conta servem para a migração inicial e só são removidos depois da confirmação do servidor, sem alterações pendentes.
- Vídeos preparados são temporários e isolados por conta. Não entram no banco, na cópia portátil ou no volume de capas; expiram após dez minutos sem iniciar a transferência e não são recuperados após reiniciar o backend.
- A API local usa `127.0.0.1` por padrão; no Compose, as portas também são vinculadas apenas a `127.0.0.1`.
- A chave OAuth é única para a instalação e deve ser preservada junto do H2. Em Compose, use o overlay opcional de secrets; no Windows, guarde o arquivo da chave fora do repositório.

Para migrar os volumes legados, use o procedimento offline em [docs/hub-storage-migration.md](docs/hub-storage-migration.md). Ele exige backend parado e backup byte a byte verificado, escreve apenas em um destino novo e não executa cutover. Mantenha `hub_data` e `hub_media` intactos até validar o novo armazenamento e completar a janela de rollback. OAuth client secret e chave de credenciais continuam externos e não são copiados pela migração. Se transferir um banco Windows com credencial Google antiga para Compose, configure primeiro a chave de instalação e o client secret no Windows, abra Google Tasks e faça uma sincronização para recriptografar o token legado com a chave nova. Sem a chave DPAPI antiga, será necessário reconectar a conta Google. A cópia portátil exportada pelo Hub inclui capas em um pacote ZIP de até 100 MiB; arquivos JSON antigos continuam importáveis, mas não contêm imagens.

## Estrutura do projeto

```text
.
├── back/                 # API Spring Boot, persistência e Dockerfile
├── front/                # Aplicação Vue, Vite, Nginx e Dockerfile
├── compose.yaml          # Orquestração do frontend e backend
└── .env.example          # Portas e configuração opcional
```
