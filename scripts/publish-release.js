const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const BUILD_GRADLE_PATH = path.join(__dirname, '../app/build.gradle.kts');
const GH_PATH_DEFAULT = 'gh';
const GH_PATH_FALLBACK = 'C:\\Program Files\\GitHub CLI\\gh.exe';
let GH_CMD = GH_PATH_DEFAULT;

try {
    // 0. Resolve 'gh' path
    try {
        execSync(`${GH_CMD} --version`, { stdio: 'ignore' });
    } catch {
        if (fs.existsSync(GH_PATH_FALLBACK)) {
            GH_CMD = `"${GH_PATH_FALLBACK}"`;
        } else {
            console.error('Error: GitHub CLI (gh) not found.');
            process.exit(1);
        }
    }

    // 1. Check Git Status
    console.log('[Release] Checking git status...');
    try {
        const status = execSync('git status --porcelain').toString();
        if (status.trim()) {
            console.error('Error: Git working directory is not clean. Commit or stash changes first.');
            process.exit(1);
        }
    } catch (e) {
        console.error('Error checking git status:', e.message);
    }

    // 2. Read and Bump Version in build.gradle.kts
    let gradleContent = fs.readFileSync(BUILD_GRADLE_PATH, 'utf8');

    // Regex for versionCode = 1
    const codeRegex = /versionCode\s*=\s*(\d+)/;
    const nameRegex = /versionName\s*=\s*"([\d\.]+)"/;

    const codeMatch = gradleContent.match(codeRegex);
    const nameMatch = gradleContent.match(nameRegex);

    if (!codeMatch || !nameMatch) {
        console.error('Error: Could not find versionCode or versionName in app/build.gradle.kts');
        process.exit(1);
    }

    const oldCode = parseInt(codeMatch[1]);
    const oldName = nameMatch[1];
    const newCode = oldCode + 1;

    // Increment patch version: 1.0.0 -> 1.0.1
    const nameParts = oldName.split('.').map(Number);
    nameParts[nameParts.length - 1]++;
    const newName = nameParts.join('.');

    console.log(`[Release] Bumping version: Code ${oldCode} -> ${newCode}, Name ${oldName} -> ${newName}`);

    gradleContent = gradleContent.replace(codeRegex, `versionCode = ${newCode}`);
    gradleContent = gradleContent.replace(nameRegex, `versionName = "${newName}"`);

    fs.writeFileSync(BUILD_GRADLE_PATH, gradleContent);

    // 3. Commit and Tag
    const tagName = `v${newName}`;
    console.log(`[Release] Committing and tagging ${tagName}...`);
    execSync(`git add "${BUILD_GRADLE_PATH}"`, { stdio: 'inherit' });
    execSync(`git commit -m "Release ${tagName}"`, { stdio: 'inherit' });
    execSync(`git tag -a ${tagName} -m "Release ${newName}"`, { stdio: 'inherit' });

    // 4. Build APK
    console.log('[Release] Building Release APK...');
    const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
    const wrapperPath = path.join(__dirname, '..', gradlew);

    if (fs.existsSync(wrapperPath)) {
        try {
            execSync(`"${wrapperPath}" assembleRelease`, { stdio: 'inherit', cwd: path.join(__dirname, '..') });
        } catch (e) {
            console.error('[Release] Build failed. Please fix build errors and try again.');
            // Revert the commit? Maybe not, allow user to fix and push.
            process.exit(1);
        }
    } else {
        console.warn('[Release] WARNING: gradle wrapper not found. Attempting to run "gradle" directly...');
        try {
            execSync('gradle assembleRelease', { stdio: 'inherit', cwd: path.join(__dirname, '..') });
        } catch (e) {
            console.error('[Release] Build failed. Gradle wrapper missing and "gradle" command failed.');
            console.error('Please generate the wrapper with Android Studio or install Gradle.');
            process.exit(1);
        }
    }

    // 5. Push to GitHub
    console.log('[Release] Pushing changes to GitHub...');
    execSync('git push origin main', { stdio: 'inherit' });
    execSync(`git push origin ${tagName}`, { stdio: 'inherit' });

    // 6. Create Release
    console.log(`[Release] Creating GitHub Release ${tagName}...`);
    const apkPath = path.join(__dirname, '../app/build/outputs/apk/release/app-release.apk');

    if (!fs.existsSync(apkPath)) {
        console.error(`[Release] Error: APK not found at ${apkPath}`);
        process.exit(1);
    }

    // Rename APK for clarity
    const releaseApkName = `decco-android-${tagName}.apk`;
    const releaseApkPath = path.join(path.dirname(apkPath), releaseApkName);
    fs.copyFileSync(apkPath, releaseApkPath);

    const cmd = `${GH_CMD} release create ${tagName} "${releaseApkPath}" --title "Release ${tagName}" --notes "Automated release ${tagName}"`;
    execSync(cmd, { stdio: 'inherit' });

    console.log('[Release] Success! Release published.');

} catch (error) {
    console.error('[Release] Unexpected error:', error);
    process.exit(1);
}
