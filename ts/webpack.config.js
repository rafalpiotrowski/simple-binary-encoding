const path = require("path");

module.exports = {
    entry: "./bench/index.ts",
    devtool: "inline-source-map",
    module: {
        rules: [
            {
                test: /\.ts$/,
                use: "ts-loader",
                exclude: /node_modules/,
            },
        ],
        noParse: [/benchmark/],
    },
    resolve: {
        extensions: [".js", ".ts"],
        extensionAlias: {
            ".js": [".js", ".ts"],
            ".ts": [".ts"],
        },
    },
    output: {
        filename: "bundle.js",
        path: path.resolve(__dirname, "dist"),
    },
    optimization: {
        minimize: false,
    },
};
