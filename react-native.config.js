module.exports = {
    dependency: {
        platforms: {
            android: {
                packageImportPath: 'import com.fastissueexport.FastIssueExportPackage;',
                packageInstance: 'new FastIssueExportPackage()',
            },
            ios: {
                // podspec is auto-discovered from the root
            },
        },
    },
};
