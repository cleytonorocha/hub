# Registro de interface — calendário Hub

## Escopo

- Navegação com Visão Geral, Calendário, Notificações e Configurações; Ferramentas é uma seção recolhível com os acessos disponíveis.
- Calendário com escalas Semana, Dia, Mês e Ano; quatro apresentações interativas em cada escala (16 combinações).
- Referência visual: captura fornecida pelo usuário. O conteúdo textual e demonstrativo da imagem não foi reutilizado.

## Implementação

- Semana: colunas, lista diária, dias úteis com fim de semana opcional e dia em foco.
- Dia: grade horária, régua de 24 horas, períodos do dia e tabela de horas selecionáveis.
- Mês: grade mensal, lista de semanas, lista cronológica e semana em foco.
- Ano: matriz de 12 meses, trimestres, lista cronológica e mês em foco.
- A data e a apresentação permanecem na URL; selecionar uma data em Semana ou Mês mantém a escala, e no Ano abre Dia.
- As visões usam abas acessíveis por clique, setas e teclas Home/End.
- Layout responsivo preserva a rolagem horizontal semanal e reorganiza os meses em telas estreitas.

## Validação

- `npm run build`: passou em 25/10/2026 após a inclusão das 16 apresentações (Vite 8.3.5; 33 módulos transformados).
- A validação visual e de console não foi concluída: a integração de navegador não estava disponível na sessão do Tester.
