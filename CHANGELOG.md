# PokeIdle Assistant 0.6.0 — versão consolidada

## Atualizações integradas

- Resumo de melhores opções por objetivo no Hunt Analyzer: XP do Pokémon, XP do treinador e Gold.
- Comparação independente dos cenários sem AoE e com AoE hipotética.
- Recomendações por objetivo também no painel Hunter flutuante.
- Estatísticas de histórico de vendas do Mercado separadas de anúncios ativos.
- Referências por espécie com contagem de vendas, mediana e faixa observada; amostras pequenas são identificadas.
- Consulta aos contadores de sessão de bolas, poções, capturas e materiais.
- Comparação descritiva da progressão das contas Principal, Alt 1 e Alt 2.
- Textos do painel atualizados para remover avisos antigos da migração de pacote.

## Limitações conhecidas que permanecem explícitas

- O Hunt Analyzer ainda é um modelo teórico: frequência relativa de espécies não confirmada, geometria real de cada Hunt não medida e AoE estimada provisoriamente em 2 alvos por golpe.
- A recomendação não substitui medição real de farm; comparar contas exige considerar nível, Hunt, Pokémon, bônus, duração e equipamento.
- As estatísticas de Mercado usam apenas registros capturados pelo leitor. O histórico pode estar incompleto; preço anunciado não é venda concluída.
- Líquido de Gemas não equivale a BRL realizado. Saque, spread e conversão não são contabilizados como lucro em reais.
- Os contadores de recursos vêm da sessão do jogo e não garantem representar todo o inventário.
- Probabilidade acumulada de Shiny continua bloqueada até validar o evento real de lançamento elegível e os bônus ativos.
- O app não compra, vende, anuncia, transfere nem automatiza ações do jogo.

## Validação

A versão só deve ser publicada depois de compilação, testes unitários disponíveis, lint, conferência do certificado de assinatura e verificação do APK final no GitHub Actions.
