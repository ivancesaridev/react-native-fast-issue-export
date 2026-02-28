const path = require('path');
const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

// The plugin lives one directory up (linked via "file:../")
const pluginRoot = path.resolve(__dirname, '..');

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
    watchFolders: [pluginRoot],
    resolver: {
        // Prevent duplicate React / React Native from the plugin's devDependencies
        nodeModulesPaths: [path.resolve(__dirname, 'node_modules')],
        // Block resolving into the plugin's own node_modules
        blockList: [
            new RegExp(`${pluginRoot.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/node_modules/.*`),
        ],
    },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
