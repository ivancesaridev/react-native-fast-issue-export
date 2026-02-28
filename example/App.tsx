/**
 * Example: How to integrate fast-issue-export into your React Native app.
 *
 * This file demonstrates the initialization and basic usage of the plugin.
 */

import React, { useEffect } from 'react';
import { StyleSheet, View, Text, Alert, Share, Platform } from 'react-native';
import { initialize, teardown } from 'fast-issue-export';

// Simulated app state — in a real app, pull from Redux/Zustand/Context/etc.
const getAppState = async () => {
    return {
        userId: 'user_abc123',
        currentScreen: 'HomeScreen',
        featureFlags: {
            darkMode: true,
            betaFeatures: false,
        },
        // Add any other diagnostic data you need
        timestamp: new Date().toISOString(),
    };
};

export default function App() {
    useEffect(() => {
        // Initialize the bug reporter on app start
        initialize({
            // This callback is invoked every time the user shakes the device.
            // Return whatever JSON you want included in the bug report.
            getAppState,

            // Called when the .zip is ready — open a share sheet
            onBugReportReady: (zipPath) => {
                Alert.alert(
                    'Bug Report Ready',
                    'A bug report has been generated. Would you like to share it?',
                    [
                        { text: 'Cancel', style: 'cancel' },
                        {
                            text: 'Share',
                            onPress: () => {
                                Share.share({
                                    url: Platform.OS === 'ios' ? zipPath : `file://${zipPath}`,
                                    title: 'Bug Report',
                                });
                            },
                        },
                    ],
                );
            },

            // Handle errors gracefully
            onError: (error) => {
                console.error('[BugReporter] Export failed:', error.message);
                Alert.alert('Error', `Bug report export failed: ${error.message}`);
            },

            // Start buffering immediately (default: true)
            autoStart: true,
        });

        // Cleanup on unmount
        return () => {
            teardown();
        };
    }, []);

    return (
        <View style={styles.container}>
            <Text style={styles.title}>My App</Text>
            <Text style={styles.subtitle}>
                Shake the device to generate a bug report 🐛
            </Text>
            <Text style={styles.info}>
                The report includes:{'\n'}
                • Last 30 seconds of screen recording{'\n'}
                • Device information{'\n'}
                • Custom app state snapshot
            </Text>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: '#1a1a2e',
        padding: 24,
    },
    title: {
        fontSize: 28,
        fontWeight: '700',
        color: '#e94560',
        marginBottom: 8,
    },
    subtitle: {
        fontSize: 16,
        color: '#eaeaea',
        marginBottom: 24,
        textAlign: 'center',
    },
    info: {
        fontSize: 14,
        color: '#a0a0b0',
        lineHeight: 22,
        textAlign: 'left',
    },
});
