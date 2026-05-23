const fs = require('fs');
const { writePsdBuffer } = require('ag-psd');
const { Jimp } = require('jimp');

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
        childrenNodes.push({
            name: txtConfig.text + index,
            left: txtConfig.left,
            top: txtConfig.top,
            right: txtConfig.right,
            bottom: txtConfig.bottom,
            text: {
                text: txtConfig.text,
                transform: [1, 0, 0, 1, txtConfig.left, txtConfig.top],
                style: {
                    font: { name: txtConfig.fontName || 'ArialMT' },
                    fontSize: txtConfig.fontSize || 24,
                    fillColor: txtConfig.color || { r: 0, g: 0, b: 0, a: 255 }
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