/**
 * FastIssueExport — Sample App
 *
 * Demonstrates initializing the plugin and triggering a bug report via shake.
 */

import React, { useEffect, useState } from 'react';
import {
  StyleSheet,
  View,
  Text,
  Alert,
  TouchableOpacity,
  StatusBar,
} from 'react-native';
import { initialize, teardown, exportBugReport, shareReport } from 'fast-issue-export';

// Simulated app state — in a real app, pull from Redux/Zustand/Context/etc.
const getAppState = async () => {
  return {
    userId: 'user_abc123',
    currentScreen: 'HomeScreen',
    featureFlags: {
      darkMode: true,
      betaFeatures: false,
    },
    timestamp: new Date().toISOString(),
  };
};

export default function App() {
  const [status, setStatus] = useState<string>('Initializing…');

  useEffect(() => {
    initialize({
      getAppState,

      onBugReportReady: (zipPath) => {
        setStatus('✅ Report ready!');
        Alert.alert(
          'Bug Report Ready',
          'A bug report has been generated. Would you like to share it?',
          [
            { text: 'Cancel', style: 'cancel' },
            {
              text: 'Share',
              onPress: () => {
                shareReport(zipPath).catch((err) =>
                  Alert.alert('Share Error', err.message)
                );
              },
            },
          ],
        );
      },

      onError: (error) => {
        console.error('[BugReporter] Export failed:', error.message);
        setStatus(`❌ Error: ${error.message}`);
        Alert.alert('Error', `Bug report export failed: ${error.message}`);
      },

      autoStart: true,
    })
      .then(() => setStatus('🟢 Buffering active — shake to report'))
      .catch((err) => setStatus(`❌ Init failed: ${err.message}`));

    return () => {
      teardown();
    };
  }, []);

  const handleManualExport = async () => {
    try {
      setStatus('⏳ Exporting…');
      const zipPath = await exportBugReport();
      setStatus('✅ Export complete!');
      Alert.alert('Bug Report', `Saved to:\n${zipPath}`);
    } catch (err: any) {
      setStatus(`❌ Export error: ${err.message}`);
    }
  };

  return (
    <View style={styles.container}>
      <StatusBar barStyle="light-content" />

      <Text style={styles.emoji}>🐛</Text>
      <Text style={styles.title}>FastIssueExport</Text>
      <Text style={styles.subtitle}>Sample App</Text>

      <View style={styles.statusBox}>
        <Text style={styles.statusLabel}>Status</Text>
        <Text style={styles.statusText}>{status}</Text>
      </View>

      <View style={styles.infoBox}>
        <Text style={styles.infoTitle}>The report includes:</Text>
        <Text style={styles.infoItem}>📹 Last 30s of screen recording</Text>
        <Text style={styles.infoItem}>📱 Device information</Text>
        <Text style={styles.infoItem}>📦 Custom app state snapshot</Text>
      </View>

      <TouchableOpacity style={styles.button} onPress={handleManualExport}>
        <Text style={styles.buttonText}>Export Bug Report Manually</Text>
      </TouchableOpacity>

      <Text style={styles.hint}>
        Or shake the device to trigger automatically
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#0f0f1a',
    padding: 24,
  },
  emoji: {
    fontSize: 48,
    marginBottom: 8,
  },
  title: {
    fontSize: 28,
    fontWeight: '800',
    color: '#e94560',
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 16,
    color: '#888',
    marginBottom: 32,
  },
  statusBox: {
    backgroundColor: '#1a1a2e',
    borderRadius: 12,
    padding: 16,
    width: '100%',
    marginBottom: 24,
    borderWidth: 1,
    borderColor: '#2a2a4a',
  },
  statusLabel: {
    fontSize: 12,
    color: '#666',
    textTransform: 'uppercase',
    marginBottom: 6,
    letterSpacing: 1,
  },
  statusText: {
    fontSize: 15,
    color: '#eaeaea',
  },
  infoBox: {
    backgroundColor: '#1a1a2e',
    borderRadius: 12,
    padding: 16,
    width: '100%',
    marginBottom: 32,
    borderWidth: 1,
    borderColor: '#2a2a4a',
  },
  infoTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#ccc',
    marginBottom: 10,
  },
  infoItem: {
    fontSize: 14,
    color: '#a0a0b0',
    lineHeight: 24,
  },
  button: {
    backgroundColor: '#e94560',
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: 10,
    marginBottom: 16,
  },
  buttonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '700',
  },
  hint: {
    fontSize: 13,
    color: '#555',
    fontStyle: 'italic',
  },
});
