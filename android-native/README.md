# Capri — Android nativo (primeira etapa)

Esta pasta contém a primeira implementação da interface Android nativa da Capri, separada do PWA existente. O site publicado não é alterado por esta branch.

## O que já está nesta etapa

- Interface Android nativa com Jetpack Compose, em tema escuro verde da Capri.
- Tela de conversa com histórico local e envio de mensagens.
- Configuração de endpoint OpenAI-compatible, modelo e chave.
- Integração HTTP com `POST /chat/completions`, compatível com Groq e OpenRouter.
- Chave da API cifrada com AES-GCM usando uma chave guardada no Android Keystore.
- Persona inicial da Capri e prompt de sistema enviado junto às mensagens.
- `minSdk 24`, `compileSdk 36` e Java 17.

## Abrir e compilar

1. Instale Android Studio e o Android SDK Platform 36.
2. Abra a pasta `android-native` no Android Studio (não a raiz do PWA).
3. Aguarde o Gradle Sync.
4. Selecione a variante `debug` e execute **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

Pelo terminal, dentro de `android-native`, com Gradle disponível:

```powershell
gradle :app:assembleDebug
```

APK debug esperado: `app/build/outputs/apk/debug/app-debug.apk`.

## Configurar a IA

Na tela Configurações, informe a chave da API. Os valores iniciais apontam para Groq:

- URL: `https://api.groq.com/openai/v1`
- Modelo: `openai/gpt-oss-20b`

Também é possível usar OpenRouter alterando a URL e o ID do modelo.

## Importante: migração por etapas

Esta é a base nativa inicial, não uma migração completa de todos os recursos do PWA. Nesta etapa ainda faltam migrar as chamadas de ferramentas (memórias/notas, abrir links, pesquisa, WhatsApp/SMS/telefone, bateria e temporizador), visão/câmera, reconhecimento de fala e leitura em voz alta. A interface web original continua intacta e pode continuar sendo usada.

A chave da API não é importada automaticamente do armazenamento do navegador: por segurança, ela deve ser inserida novamente nas configurações do app nativo.
