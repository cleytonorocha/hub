# Hub

Aplicação web para organizar tarefas pessoais em um calendário, acompanhar prazos e personalizar a forma de visualizar cada período.

> **Status:** tarefas locais e calendário estão disponíveis. A integração com Google Tasks está pausada. A tela de baixar vídeos é apenas uma interface; ela não inicia downloads.

## Visão geral

O Hub combina uma interface Vue com uma API Spring Boot. As tasks locais são salvas no banco H2 e podem ser criadas sem conexão com serviços externos.

### Calendário

Alterne entre as visualizações diária, semanal, mensal e anual. Cada uma oferece apresentações próprias, e as configurações permitem escolher qual apresentação abrir por padrão. Clique em uma data ou horário para iniciar uma task com esses dados preenchidos.

| Dia | Semana |
| --- | --- |
| ![Mockup de referência do calendário diário](front/design-reference/calendar/calendar-day.png) | ![Mockup de referência do calendário semanal](front/design-reference/calendar/calendar-week.png) |

| Mês | Ano |
| --- | --- |
| ![Mockup de referência do calendário mensal](front/design-reference/calendar/calendar-month.png) | ![Mockup de referência do calendário anual](front/design-reference/calendar/calendar-year.png) |

As imagens acima são mockups de referência incluídos no projeto.

### Recursos

- **Tasks locais:** título, data, horário, categoria, cor, prioridade, observações e estado de conclusão.
- **Notificações:** visão de tasks vencidas, para hoje e para amanhã.
- **Configurações:** resumo semanal, valores padrão para novas tasks e apresentação padrão de cada visualização do calendário.
- **Baixar vídeos:** tela inicial com seleção de plataforma e validação local do formato e domínio do link. A integração de download ainda não está implementada.
- **Layout adaptável:** navegação lateral que pode ser recolhida e interface ajustada para telas menores.

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

`docker compose down` mantém o volume `hub_data`. Para apagar também o banco dos containers, use `docker compose down --volumes`. Isso remove os dados salvos no volume.

## Dados e privacidade

- Sem Docker, o H2 fica em `%LOCALAPPDATA%\Hub\data` no Windows.
- Com Docker, o H2 fica no volume nomeado `hub_data`. Esse banco é independente do arquivo local e não recebe os dados locais automaticamente.
- Preferências de apresentação e de tasks ficam no `localStorage` do navegador.
- A API local usa `127.0.0.1` por padrão; no Compose, as portas também são vinculadas apenas a `127.0.0.1`.
- A chave OAuth atual usa Windows DPAPI. Por isso, a conexão Google não funciona no container Linux.

Para fazer backup do banco local, pare o backend e copie os arquivos de `%LOCALAPPDATA%\Hub\data`. Para o banco do Compose, mantenha o volume `hub_data`; não use `down --volumes` se quiser preservar os registros.

## Estrutura do projeto

```text
.
├── back/                 # API Spring Boot, persistência e Dockerfile
├── front/                # Aplicação Vue, Vite, Nginx e Dockerfile
├── compose.yaml          # Orquestração do frontend e backend
└── .env.example          # Portas e configuração opcional
```
