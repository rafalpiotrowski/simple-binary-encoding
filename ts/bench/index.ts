import { Bench } from "tinybench";
import { DirectBuffer, DefaultMutableDirectBuffer } from "agrona";
import { MessageHeaderEncoder } from "../../generated/ts/MessageHeaderEncoder.g";
import { MessageHeaderDecoder } from "../../generated/ts/MessageHeaderDecoder.g";
import { CarEncoder } from "../../generated/ts/CarEncoder.g";
import { CarDecoder } from "../../generated/ts/CarDecoder.g";

const sbeMessage = createMessage();

function createMessage(): Uint8Array {
    const buffer = new DefaultMutableDirectBuffer(256);
    const encoder = new CarEncoder();
    const header = new MessageHeaderEncoder();
    encoder.wrapAndApplyHeader(buffer, 0, header);
    const engine = encoder.engine();
    engine.capacity(2000);
    engine.numCylinders(8);
    engine.maxRpm(9000);
    const data = buffer.byteArray();
    // console.log(`Created message size: ${data.length}`);
    return data;
}

function handler(buffer: Uint8Array): string {
    const dbuf = new DefaultMutableDirectBuffer();
    dbuf.wrap(buffer);
    const header = new MessageHeaderDecoder();
    const decoder = new CarDecoder();
    header.wrap(dbuf, 0);
    decoder.wrap(
        dbuf as DirectBuffer,
        header.encodedLength(),
        header.blockLength(),
        header.version(),
    );
    const result = decoder.toString();
    // console.log(`${result}`);
    return result;
}

function handlerReuse(
    buffer: Uint8Array,
    dbuf: DefaultMutableDirectBuffer,
    header: MessageHeaderDecoder,
    decoder: CarDecoder,
) {
    dbuf.wrapArray(buffer, 0, buffer.length);
    header.wrap(dbuf, 0);
    decoder.wrap(
        dbuf,
        header.encodedLength(),
        header.blockLength(),
        header.version(),
    );
    const result = decoder.toString();
    // console.log(`Login pwd: ${result}`);
    return result;
}

function startBenchmark() {
    (async () => {
        const benchEncode = new Bench({
            iterations: 5_000_000,
            warmupIterations: 1_000_000,
        });
        benchEncode.add("SBE/encode", () => {
            createMessage();
        });

        await benchEncode.run();

        console.table(benchEncode.table());

        const benchDecode = new Bench({
            iterations: 5_000_000,
            warmupIterations: 1_000_000,
        });

        benchDecode.add("SBE/decode", () => {
            handler(sbeMessage);
        });

        await benchDecode.run();

        console.table(benchDecode.table());

        const dbuf = new DefaultMutableDirectBuffer();
        const header = new MessageHeaderDecoder();
        const decoder = new CarDecoder();

        const benchDecodeReuse = new Bench({
            iterations: 5_000_000,
            warmupIterations: 1_000_000,
        });

        benchDecodeReuse.add("SBE/decode-reuse", () => {
            handlerReuse(sbeMessage, dbuf, header, decoder);
        });

        await benchDecodeReuse.run();

        console.table(benchDecodeReuse.table());
    })();
}

const buttonContainer = document.getElementById("buttons");

function addButton(text: string, handler: () => void) {
    const button = document.createElement("button");

    button.innerText = text;

    button.addEventListener("click", handler);

    buttonContainer?.appendChild(button);
}

addButton("Run Benchmark", () => {
    console.log("Starting benchmark");
    startBenchmark();
});

const COUNT = 10_000_000;

addButton("Encode SBE messages", () => {
    console.log("Encoding SBE messages");
    const t0 = performance.now();
    for (let i = 0; i < COUNT; i++) {
        createMessage();
    }
    const t1 = performance.now();
    console.log(`Done in ${t1 - t0} milliseconds.`);
});

addButton("Decode SBE messages", () => {
    console.log("Decoding SBE messages");
    const t0 = performance.now();
    for (let i = 0; i < COUNT; i++) {
        handler(sbeMessage);
    }
    const t1 = performance.now();
    console.log(`Done in ${t1 - t0} milliseconds.`);
});
