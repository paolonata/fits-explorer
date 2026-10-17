# Analisi del riferimento HTML

Riferimento: FITS-Explorer-Minimal-Download.html fornito dall'utente.

La UI usa sfondo #080c13, viewer #030609, pannello #111924, accento #a7c0ff. Viewer grande, due pulsanti, quattro slider e Auto; nessuna opzione Bayer/SCNR esposta. Questi elementi sono ripresi in Compose.

La pipeline HTML legge dati FITS big endian in Float32, applica scaling, riconosce RGGB da BAYERPAT, campiona percentili 0.001/0.9995, precalcola una preview RGB, applica SCNR average-neutral e asinh con LUT 4096. Auto imposta nero 0, bianco 100, gamma 1, stretch 5. La versione nativa mantiene questa mappatura dei controlli.

Correzioni intenzionali:

- L'HTML esporta sourceCanvas (anteprima); Android esporta la cache lineare completa, con le stesse impostazioni e senza zoom/pan.
- Il parser HTML interrompe i valori al primo slash anche dentro stringhe e non verifica END; il parser Kotlin gestisce stringhe FITS quotate/escape e padding, END, truncation, BLANK, NAXIS esattamente 2 e dimensioni senza overflow.
- La media 3×3 HTML può sfocare anche i campioni Bayer misurati: il debayer bilineare Kotlin conserva i campioni originali e interpola solo i canali mancanti.
- Il debayer completo viene calcolato una sola volta su thread di lavoro e salvato su cache privata; il FITS originale non viene modificato. SCNR e LUT vengono applicati in rendering, senza alterare i dati lineari.
- Il parser conserva un'origine fisica double e salva differenze float per evitare la perdita dei piccoli segnali su pedestal unsigned a 32 bit.
- MediaStore sostituisce il download web, con pending/commit/rollback e apertura del JPG. Il supporto minimo API 29 elimina la necessità dei permessi legacy.
- Le righe sono orientate secondo ROWORDER o convenzione FITS. Il riferimento web non applica il ribaltamento convenzionale.

Nessuna affermazione di equivalenza numerica completa con PixInsight: SCNR corrisponde alla variante average-neutral al 100% del riferimento, non all'intera pipeline di gestione colore PixInsight.
