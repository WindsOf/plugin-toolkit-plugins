const fs = require('fs');
const { writePsdBuffer } = require('ag-psd');
const { Jimp } = require('jimp');



async function generatePSD(jsonPath, outputPath) {
    const payload = JSON.parse(fs.readFileSync(jsonPath, 'utf8'));

    if (!payload.backgroundImage) {
        throw new Error("Missing 'backgroundImage' in JSON payload.");
    }

    const bgImage = await Jimp.read(payload.backgroundImage);

    const canvasData = {
        width: bgImage.bitmap.width,
        height: bgImage.bitmap.height,
        data: new Uint8ClampedArray(bgImage.bitmap.data)
    };

    const childrenNodes = [
        {
            name: 'background',
            imageData: canvasData,
            left: 0, top: 0, right: canvasData.width, bottom: canvasData.height
        },
        {
            name: 'clean',
            imageData: canvasData,
            left: 0, top: 0, right: canvasData.width, bottom: canvasData.height
        }
    ];

    const textsList = payload.texts || [];

    textsList.forEach((txtConfig, index) => {
        const textContent = txtConfig.text || "";
        let fontName = txtConfig.fontName || 'AnimeAce2.0BB';

        const fontSize = txtConfig.fontSize || 24;
        const left = txtConfig.left;
        const top = txtConfig.top;
        const right = txtConfig.right;
        const bottom = txtConfig.bottom;

        if (left === undefined || top === undefined || right === undefined || bottom === undefined) {
            throw new Error(`Missing mandatory bounding box coordinates (left, top, right, bottom) for text layer ${index}`);
        }

        const boxWidth = right - left;
        const boxHeight = bottom - top;
        
        const strokeSize = txtConfig.strokeSize !== undefined ? txtConfig.strokeSize : 3;

        const layerName = textContent.length > 20 ? textContent.substring(0, 20) : (textContent || `Testo ${index}`);

        childrenNodes.push({
            name: layerName,
            left: left,
            top: top,
            right: right,
            bottom: bottom,
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
                text: textContent, // Nessun wrapping manuale!
                shapeType: 'box', // Trasforma in Paragrafo nativo di Photoshop
                boxBounds: [0, 0, boxWidth, boxHeight], // Formato: [Left, Top, Right, Bottom]
                transform: [1, 0, 0, 1, left, top], // Offset globale del livello
                left: 0,
                top: 0,
                right: boxWidth,
                bottom: boxHeight,
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