# PokeIdle Assistant

Companion Android de uso pessoal para acompanhar o PokeIdle IO no próprio aparelho.

## Objetivos

- Manter três sessões independentes do jogo em janelas flutuantes.
- Permitir minimizar cada janela em uma bolinha sem destruir sua WebView.
- Manter a janela Hunt comunitária já integrada.
- Oferecer um dashboard local para análises de progressão, economia e Shiny.
- Não automatizar combate, captura, movimentação ou outras ações do jogo.

## Estado do desenvolvimento

A branch `assistant-foundation` contém os fontes Android separados do workflow antigo e uma variante de teste com identificador de pacote próprio:

- Pacote de teste: `com.duascontas.pokeidle.assistant`
- App original: permanece separado e não é substituído pela variante de teste.
- Assinatura: debug keystore padrão do Android Gradle Plugin.
- Dashboard: estrutura visual inicial; métricas ainda não estão conectadas ao jogo.

## Compilação

O GitHub Actions executa:

```bash
gradle assembleDebug --no-daemon --stacktrace
```

O APK de teste é publicado como artifact do workflow `Build APK` na branch `assistant-foundation`. Ele não é publicado como Release automaticamente.

## Limites importantes

O dashboard não deve exibir valores inventados. Até existir uma fonte de dados validada e permitida, os indicadores permanecem vazios. A integração deve respeitar as regras do jogo e evitar coleta de credenciais, cookies ou dados privados de sessão.

## Aparelho-alvo

Desenvolvimento e validação prioritários em POCO M4 5G, Android 14 / HyperOS.
