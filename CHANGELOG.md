# PokeIdle Assistant 0.7.0 — Hunter & Prey Lab

## Correções de fluxo e diagnóstico

- O pedido do Hunt Analyzer agora recebe confirmação explícita da janela correta da conta.
- Se a janela não estiver aberta, se a ponte de captura estiver indisponível ou se os dados ainda não estiverem prontos, o painel informa o motivo em vez de aguardar indefinidamente.
- O painel reconhece resultados sem dados suficientes e erros da análise; o Hunter flutuante também encerra a espera com uma mensagem clara.
- O diagnóstico técnico preserva o recibo do último pedido e os contadores de captura por conta.

## Prey · laboratório experimental

- Novo registro manual de amostras com Prey marcada como ON ou OFF.
- Mede diferenças dos contadores nativos da sessão: XP Pokémon, XP treinador, Gold, abates, capturas, Shinies e bolas.
- Guarda Hunt, Pokémon ativo, nível e assinatura dos bônus para verificar se as condições permaneceram estáveis.
- Amostras com menos de 5 minutos ou com Hunt, Pokémon, nível ou bônus alterados são registradas, mas excluídas das médias comparativas.
- Histórico local limitado às 40 amostras mais recentes por conta.
- As médias ON/OFF só comparam pares com a mesma Hunt, Pokémon ativo, nível e assinatura dos bônus; amostras curtas ou com contexto alterado ficam fora das médias.
- A duração usa o tempo ativo informado pelo jogo quando disponível, com fallback explicitamente identificado para o tempo entre marcações.
- Telemetria passiva procura nomes/campos explícitos relacionados a Prey/presa sem presumir que a ausência desses sinais prove ausência da mecânica.

## Interface e desempenho

- Hierarquia visual mais clara, cards com contraste e bordas mais suaves e cabeçalho com gradiente.
- Telas de Progressão, Hunts, Pokémon, Recursos, Shiny e Mercado recarregam o snapshot da conta antes de exibir os dados, evitando depender de um painel antigo em memória.
- Shiny mostra contadores e eventos observados, incluindo o consumo de bolas como proxy; não exibe probabilidade acumulada não validada.
- Mercado diferencia ausência de mensagem de Mercado de mensagem recebida sem linhas, orientando a coleta da aba correta.
- Mantida a interface nativa Android, sem adicionar bibliotecas visuais pesadas.
- Cálculos continuam sob demanda; o leitor mantém telemetria limitada e amostras de eventos com tamanho máximo.

## Limitações importantes

- Não encontrei documentação pública oficial verificável que descreva uma mecânica chamada Prey ou sua fórmula. O módulo é um instrumento de teste A/B manual: a condição ON/OFF é declarada pelo usuário, não controlada pelo app. Uma diferença observada não prova causalidade.
- O app ainda precisa ser testado dentro do jogo em cada conta. O recibo de pedido melhora a identificação do ponto de falha, mas não comprova que a análise da Principal já funciona no aparelho.
- O Hunt Analyzer continua sendo uma estimativa teórica; geometria real e frequência de spawn precisam de validação prática.
- Nenhuma compra, venda, anúncio ou ação automática é executada.

## Validação exigida

Publicar apenas após CI concluir compilação, testes unitários disponíveis, lint, verificação da assinatura estável e conferência do APK final. Build aprovado não equivale a teste no aparelho.
