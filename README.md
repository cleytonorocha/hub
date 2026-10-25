# Hub

Aplicação pessoal com frontend Vue 3 + Vite e backend Spring Boot.

## Executar

Na pasta `front`, instale as dependências e execute `npm run dev`. O frontend inicia na porta `8081`; se estiver ocupada, o Vite encerra sem escolher outra porta.

Na pasta `back`, execute `mvn spring-boot:run`. O backend usa H2 em memória; os dados são descartados ao encerrar a aplicação e ainda não há tabelas de domínio.

A preferência de exibição do resumo semanal é armazenada no `localStorage` deste navegador.
