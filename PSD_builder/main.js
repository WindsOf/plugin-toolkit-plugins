const fs = require('fs');
const { writePsdBuffer } = require('ag-psd');
const { Jimp } = require('jimp');

function wrapText(text, maxWidth, fontSize) {
    // Stima della larghezza di un carattere medio (0.6 * fontSize è uno standard)
    const approxCharWidth = fontSize * 0.6; 
    const maxChars = Math.max(1, Math.floor(maxWidth / approxCharWidth));
    
    const words = text.split(/\s+/);
    let lines = [];
    let currentLine = "";
    
    words.forEach(word => {
        if ((currentLine + word).length > maxChars) {
            if (currentLine.length > 0) {
                lines.push(currentLine.trim());
                currentLine = word + " ";
            } else {
                lines.push(word);
                currentLine = "";
            }
        } else {
            currentLine += word + " ";
        }
    });
    if (currentLine.length > 0) {
        lines.push(currentLine.trim());
    }
    
    return lines.join('\r'); // Photoshop usa \r per i ritorni a capo nei PSD
}

async function generatePSD(jsonPath, outputPath) {
    const payload = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));

    const bgImage = await Jimp.read(payload.backgroundImage);

    const canvasData = {
        width: bgImage.bitmap.width,
        height: bgImage.bitmap.height,
        data: new Uint8ClampedArray(bgImage.bitmap.data)
    };

    const childrenNodes = [
        {
            name: 'Background',
            imageData: canvasData,
            left: 0, top: 0, right: canvasData.width, bottom: canvasData.height
        }
    ];

    payload.texts.forEach((txtConfig, index) => {
        let fontName = txtConfig.fontName || 'Anime Ace 2.0 BB';
        
        // Se il plugin Kotlin gli ha passato ArialMT di default, sovrascriviamo
        if (fontName === 'ArialMT' || fontName === 'Anime ACE 2.0') {
            fontName = 'Anime Ace 2.0 BB';
        }

        const fontSize = txtConfig.fontSize || 24;
        const boxWidth = txtConfig.right - txtConfig.left;
        const boxHeight = txtConfig.bottom - txtConfig.top;
        
        // Aggiungiamo l'andata a capo manuale in base alla grandezza stimata
        const wrappedText = wrapText(txtConfig.text, boxWidth, fontSize);

        // Calcolo dell'offset verticale per centrare il testo nel box
        const lines = wrappedText.split('\r');
        const textHeight = lines.length * fontSize * 1.2;
        let verticalOffset = (boxHeight - textHeight) / 2;
        if (verticalOffset < 0) verticalOffset = 0;

        const strokeSize = txtConfig.strokeSize !== undefined ? txtConfig.strokeSize : 3;

        childrenNodes.push({
            name: `Testo ${index}`,
            left: txtConfig.left,
            top: txtConfig.top,
            right: txtConfig.right,
            bottom: txtConfig.bottom,
            ...(strokeSize > 0 ? {
                effects: {
                    stroke: [{
                        enabled: true,
                        present: true,
                        showInDialog: true,
                        size: { value: strokeSize, units: 'Pixels' },
                        position: 'outside',
                        fillType: 'color',
                        blendMode: 'normal',
                        opacity: 1,
                        color: { r: 255, g: 255, b: 255, a: 1 }
                    }]
                }
            } : {}),
            text: {
                text: wrappedText,
                transform: [1, 0, 0, 1, txtConfig.left, txtConfig.top + verticalOffset],
                left: 0,
                top: 0,
                right: boxWidth,
                bottom: Math.max(textHeight, boxHeight - verticalOffset),
                style: {
                    font: { name: fontName },
                    fontSize: fontSize,
                    fillColor: txtConfig.color || { r: 0, g: 0, b: 0, a: 255 }
                },
                paragraphStyle: {
                    justification: 'center'
                }
            }
        });
    });

    const psdLayout = {
        width: canvasData.width,
        height: canvasData.height,
        children: childrenNodes
    };

    const buffer = writePsdBuffer(psdLayout);
    fs.writeFileSync(outputPath, buffer);
    console.log(`PSD generato con successo: ${outputPath}`);
}

const args = process.argv.slice(2);
if (args.length < 2) {
    console.error("Uso: psd-generator.exe <config.json> <output.psd>");
    process.exit(1);
}

generatePSD(args[0], args[1]).catch(err => {
    console.error("Errore critico:", err);
    process.exit(1);
});