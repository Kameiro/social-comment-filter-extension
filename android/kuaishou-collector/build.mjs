import { execFileSync } from 'node:child_process';
import { mkdirSync, readdirSync, existsSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const project = dirname(fileURLToPath(import.meta.url));
const runtime = resolve(project, '../runtime/collector');
const sdk = process.env.ANDROID_HOME || join(homedir(), 'Library/Android/sdk');
const java = process.env.JAVA_HOME || '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home';
const tools = join(sdk, 'build-tools/36.0.0');
const android = join(sdk, 'platforms/android-36/android.jar');
const env = { ...process.env, JAVA_HOME: java, PATH: `${java}/bin:${process.env.PATH}` };
const run = (tool, args, options = {}) => execFileSync(tool, args, { env, stdio: 'inherit', ...options });
const sources = path => readdirSync(path, { withFileTypes: true }).flatMap(entry =>
  entry.isDirectory() ? sources(join(path, entry.name)) : entry.name.endsWith('.java') ? [join(path, entry.name)] : []);
mkdirSync(runtime, { recursive: true, mode: 0o700 });
const aar = join(runtime, 'api-102.0.0.aar');
if (!existsSync(aar)) run('curl', ['-fL', '--retry', '2', '-o', aar,
  'https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar']);
if (createHash('sha256').update(readFileSync(aar)).digest('hex') !==
  '423484a6e1807e7a423c4b88fcd8176d104318259d91791877fed88fe91479d0') throw new Error('API checksum mismatch');
const api = join(runtime, 'api.jar');
writeFileSync(api, execFileSync('unzip', ['-p', aar, 'classes.jar']));
const nano = join(runtime, 'nanohttpd-2.3.1.jar');
if (!existsSync(nano)) run('curl', ['-fL', '--retry', '2', '-o', nano,
  'https://repo.maven.apache.org/maven2/org/nanohttpd/nanohttpd/2.3.1/nanohttpd-2.3.1.jar']);
if (createHash('sha256').update(readFileSync(nano)).digest('hex') !==
  'de864c47818157141a24c9acb36df0c47d7bf15b7ff48c90610f3eb4e5df0e58') throw new Error('HTTP library checksum mismatch');
const out = join(runtime, 'build');
rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'classes'), { recursive: true });
mkdirSync(join(out, 'dex'), { recursive: true });
run(join(java, 'bin/javac'), ['-encoding', 'UTF-8', '--release', '17', '-cp', `${android}:${api}:${nano}`,
  '-d', join(out, 'classes'), ...sources(join(project, 'src'))]);
run(join(java, 'bin/jar'), ['--create', '--file', join(out, 'code.jar'), '-C', join(out, 'classes'), '.']);
run(join(tools, 'd8'), ['--min-api', '29', '--lib', android, '--classpath', api,
  '--output', join(out, 'dex'), join(out, 'code.jar'), nano]);
run(join(tools, 'aapt2'), ['compile', '--dir', join(project, 'res'), '-o', join(out, 'res.zip')]);
const raw = join(out, 'unsigned.apk');
run(join(tools, 'aapt2'), ['link', '-I', android, '--manifest', join(project, 'AndroidManifest.xml'),
  '-o', raw, join(out, 'res.zip')]);
run('zip', ['-q', '-u', raw, 'classes.dex'], { cwd: join(out, 'dex') });
run('zip', ['-q', '-r', raw, 'META-INF'], { cwd: join(project, 'resources') });
const aligned = join(out, 'aligned.apk');
run(join(tools, 'zipalign'), ['-f', '4', raw, aligned]);
const key = join(runtime, 'development.keystore');
if (!existsSync(key)) run(join(java, 'bin/keytool'), ['-genkeypair', '-keystore', key, '-storepass', 'android',
  '-keypass', 'android', '-alias', 'development', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
  '-dname', 'CN=Local Android Lab']);
const apk = join(runtime, 'kuaishou-collector-debug.apk');
run(join(tools, 'apksigner'), ['sign', '--ks', key, '--ks-pass', 'pass:android', '--out', apk, aligned]);
run(join(tools, 'apksigner'), ['verify', apk]);
console.log(JSON.stringify({ apk, api: '102.0.0', developmentOnly: true }, null, 2));
