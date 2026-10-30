# Hub

Aplicação web para organizar tarefas pessoais em um calendário, acompanhar prazos e personalizar a forma de visualizar cada período.

> **Status:** tarefas locais e calendário estão disponíveis. A integração com Google Tasks está pausada. A tela de baixar vídeos é apenas uma interface; ela não inicia downloads.

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
- **Configurações:** resumo semanal, valores padrão para novas tasks e apresentação padrão de cada visualização do calendário.
- **Baixar vídeos:** tela inicial com seleção de plataforma e validação local do formato e domínio do link. A integração de download ainda não está implementada.
- **Layout adaptável:** navegação lateral que pode ser recolhida e interface ajustada para telas menores.

### Contas e permissões

Na primeira abertura, a instalação apresenta um assistente local para criar a conta Administrador. O assistente encerra após a criação e atribui a essa conta os registros e capas que já existiam. Não há usuário nem senha padrão. Depois, o Administrador cria contas e perfis nomeados com permissões para tarefas, biblioteca, configurações e ferramentas. Os registros pessoais e as cópias portáteis pertencem à conta autenticada.

O login usa sessão do servidor, cookie HttpOnly, proteção CSRF e limite de tentativas. O Compose publica a interface e a API somente em `127.0.0.1`; o backend executado fora do Docker também usa bind local. **O primeiro setup sem token só está aprovado para esse acesso local. Não publique o app em LAN ou na internet**; uma implantação remota precisa desativar o setup aberto e definir um mecanismo administrativo de bootstrap seguro antes de expor a aplicação.

A integração Google Tasks permanece indisponível nesta versão multiusuário porque o estado e as credenciais antigos ainda não têm isolamento funcional por conta. Os dados existentes são preservados e associados ao Administrador inicial.

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
- yt-dlp e FFmpeg no `PATH` para executar o serviço de download fora do Docker
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

O serviço de download usa o executável `yt-dlp` e o FFmpeg para reunir faixas de áudio e vídeo quando necessário. No backend local, configure `HUB_VIDEO_DOWNLOAD_YT_DLP_COMMAND` se o executável não estiver disponível como `yt-dlp`. A imagem Docker instala essas dependências.

## Executar com Docker Compose

Na raiz do projeto:

```sh
docker compose up --build -d
```

Abra <http://localhost:8081>. O front é compilado e servido pelo Nginx, que encaminha `/api` ao back. A API também é publicada em <http://localhost:8080>. Os containers possuem healthchecks, e o front aguarda a API ficar pronta.

Para personalizar as portas, copie `.env.example` para `.env` e altere `FRONTEND_PORT` ou `BACKEND_PORT`. Por padrão, as portas são publicadas somente em `127.0.0.1`.

```sh
docker compose ps
docker compose logs -f
docker compose down
```

`docker compose down` mantém os volumes `hub_data` e `hub_media`. O comando `docker compose down --volumes` apaga os dois, incluindo o banco e as imagens enviadas.

## Dados e privacidade

- Sem Docker, o H2 fica em `%LOCALAPPDATA%\Hub\data` no Windows.
- Com Docker, o H2 fica no volume nomeado `hub_data`. As imagens da Biblioteca de leitura ficam no volume separado `hub_media`, organizadas em `reading-library/covers`. Esses dados são independentes do arquivo local e não recebem os dados locais automaticamente.
- Sem Docker, as imagens ficam em `%LOCALAPPDATA%\Hub\media\reading-library\covers`, fora do repositório.
- Preferências de apresentação e de tasks ficam no `localStorage` do navegador.
- A API local usa `127.0.0.1` por padrão; no Compose, as portas também são vinculadas apenas a `127.0.0.1`.
- A chave OAuth atual usa Windows DPAPI. Por isso, a conexão Google não funciona no container Linux.

Antes de iniciar esta versão, pare o backend e faça uma cópia física consistente. Copie juntos banco e mídia: `%LOCALAPPDATA%\Hub\data` e `%LOCALAPPDATA%\Hub\media` sem Docker; `hub_data` e `hub_media` no Compose. A primeira conta executa a atribuição transacional dos registros antigos; após o commit, uma migração versionada aplica as restrições de propriedade. Se essa etapa não concluir, o backend bloqueia o login e os endpoints privados até a migração ser concluída. A cópia portátil exportada pelo Hub inclui capas em um pacote ZIP de até 100 MiB; arquivos JSON antigos continuam importáveis, mas não contêm imagens. Não use `down --volumes` se quiser preservar banco e capas.

## Estrutura do projeto

```text
.
├── back/                 # API Spring Boot, persistência e Dockerfile
├── front/                # Aplicação Vue, Vite, Nginx e Dockerfile
├── compose.yaml          # Orquestração do frontend e backend
└── .env.example          # Portas e configuração opcional
```
