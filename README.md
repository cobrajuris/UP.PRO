# UP.PRO — aplicativo

Aplicativo de treinos em português do Brasil, construído com o **UP.PRO Design System FINAL**,
os **1.324 exercícios** do Exercises Dataset (com imagens e animações) e o rastreamento de
corrida com mapa inspirado no **RootStep** e no **RunTrack**.

## O que tem

- **Início** com mapa ao vivo (GPS), meta de km da semana, treino do dia, semana, anéis de progresso e hidratação.
- **Corrida e caminhada**: mapa em tela cheia, trajeto, tempo, distância, ritmo médio, último km, km/h, calorias e altitude.
  Pausa em trechos, "segure para encerrar", resumo com parciais por km, histórico, compartilhar e exportar **GPX**.
  A atividade continua sendo gravada se você navegar pelo app e é salva mesmo se a página recarregar.
- **Treinos**: 16 treinos (casa, halteres, academia) com séries, repetições e descanso ajustados ao seu nível e objetivo.
- **Biblioteca** com os 1.324 exercícios: busca, filtros por região e equipamento, animação, músculos e passo a passo traduzidos.
- **Execução guiada**: cronômetro, contagem de descanso com bipe e vibração, tela sempre ligada.
- **Plano semanal** com troca de treino por dia e integração com o **Google Agenda** (evento semanal ou arquivo `.ics`).
- **Progresso**: sequência de dias, gráficos, peso e IMC, histórico em calendário e conquistas.
- Funciona **offline** depois da primeira abertura (PWA). Todos os dados ficam no aparelho.

## Rodar no computador

```bash
npm install
npm run build
npm run preview
```

Abra o endereço mostrado (ex.: `http://localhost:4173`). Para desenvolver com recarga automática: `npm run dev`.

## Instalar o APK no Android

O arquivo **UP.PRO.apk** (143 MB) já vem pronto, com todos os exercícios, imagens e mapas embutidos.

1. Copie o `UP.PRO.apk` para o celular.
2. Toque no arquivo. O Android vai pedir para **permitir a instalação de fontes desconhecidas** — autorize para o app onde você abriu o arquivo (Arquivos ou Chrome).
3. Toque em **Instalar** e depois em **Abrir**.
4. Na primeira corrida, permita o acesso à **localização**.

> O APK é assinado com a chave de depuração do Android, o que basta para instalar no seu aparelho.
> Para publicar na Play Store seria preciso gerar uma chave própria e rodar `./gradlew bundleRelease`.

### Gerar o APK de novo

Precisa do JDK 17 e do Android SDK 34. Com eles instalados:

```bash
npm run build
npx cap sync android
cd android
./gradlew assembleDebug
```

O APK sai em `android/app/build/outputs/apk/debug/app-debug.apk`.
Aponte `android/local.properties` para o seu SDK usando barras normais: `sdk.dir=C:/caminho/para/sdk`.

## Instalar como app web (PWA)

O GPS e a instalação exigem **HTTPS**. Publique a pasta `dist/` em qualquer hospedagem estática com HTTPS
(Netlify, Vercel, Cloudflare Pages ou GitHub Pages). Depois, no Chrome do Android:

1. Abra o endereço do app.
2. Toque em **Instalar aplicativo** (no Perfil) ou no menu ⋮ → **Instalar app**.
3. Permita a localização quando começar a primeira corrida.

> Limitação de apps web: com a tela apagada ou o app em segundo plano, o Android pode pausar o GPS.
> Por isso o app mantém a tela ligada durante a corrida. Para rastrear com a tela desligada seria preciso
> empacotar como app nativo (por exemplo com Capacitor + serviço em primeiro plano, como no RunTrack).

## Estrutura

```
src/ds/            componentes do UP.PRO Design System (Button, Card, WorkoutCard…)
src/tokens/        tokens de cor, tipografia, espaçamento, raio, sombra e movimento
src/screens/       Início, Treinos, Execução, Corrida, Progresso, Plano, Perfil, Onboarding
src/lib/           estado local, treinos, formatação pt-BR, Google Agenda, GPS e rastreador
src/Mapa.jsx       mapa (Leaflet + OpenStreetMap)
scripts/           geração dos ícones e do JSON de exercícios em português
data/tr/           traduções pt-BR de nomes e instruções
public/media/      imagens e animações dos exercícios
```

## Créditos e licenças

- Animações e imagens dos exercícios © **Gym visual** — gymvisual.com (uso em 180×180 conforme os termos do dataset).
- Dados dos exercícios: Exercises Dataset (MIT), traduzidos para o português.
- Mapas © colaboradores do **OpenStreetMap**. Lógica de rastreamento portada de RootStep (MIT) e RunTrack.
- Ícones: Lucide. Fontes: Plus Jakarta Sans e Jost (Google Fonts).
