# Ascolta

App Android per ascoltare: **musica** (YouTube Music in una pagina dell'app), **podcast** e **radio**, con un lettore unico.

- **Per te**: quello che stavi ascoltando, le puntate nuove, la coda, le radio preferite
- **Musica**: YouTube Music, con audio in background, salto delle pubblicità e testo sincronizzato delle canzoni (LRCLIB)
- **Podcast**: cerca (Apple Podcasts), segui, ascolta, coda («Ascolta dopo»), velocità per ogni podcast, scarica le puntate (anche in automatico col wifi), salta i silenzi
- **Radio**: le più ascoltate in Italia, per regione, ricerca, preferite (radio-browser.info), **sveglia con la radio**
- **Lettore** a schermo intero con orologio, velocità, **timer del sonno**, notifica e tasti sul blocco schermo
- Esporta / importa i tuoi dati; importa ed esporta i podcast in **OPML**
- Tema chiaro, scuro o come il telefono

I libri ePub con la lettura ad alta voce restano in Web Launcher.

## Costruzione

```
gradle testDebugUnitTest assembleDebug
```

Su GitHub la costruzione parte a ogni modifica; sul ramo `main` crea la release con l'APK.
Per aggiornamenti installabili sopra la versione precedente servono i secret
`ASCOLTA_KEYSTORE_BASE64` e `ASCOLTA_KEYSTORE_PASSWORD` (alias `weblauncher`).

## Limiti

- La musica è la pagina web di YouTube Music: se YouTube la cambia, qualche tasto del lettore (avanti/indietro) può smettere di funzionare.
- La musica in background dipende da Android: se si ferma con lo schermo spento, va controllato il risparmio batteria.
- Niente ricerca vocale e niente richieste a voce (restano in Web Launcher).
