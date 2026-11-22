import { execFileSync } from 'node:child_process';
import { mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const project = dirname(fileURLToPath(import.meta.url));
const runtime = resolve(project, '../runtime/collector');
const java = process.env.JAVA_HOME || '/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home';
const json = join(runtime, 'json.jar');
mkdirSync(join(runtime, 'test-classes'), { recursive: true });
if (!existsSync(json)) execFileSync('curl', ['-fL', '-o', json, 'https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/javac'), ['--release', '17', '-cp', json, '-d', join(runtime, 'test-classes'),
  join(project, 'src/dev/local/commentcollector/DouyinRootPolicy.java'), join(project, 'tests/DouyinRootPolicyTest.java'),
  join(project, 'src/dev/local/commentcollector/TaskProgress.java'), join(project, 'tests/TaskProgressTest.java'),
  join(project, 'src/dev/local/commentcollector/KuaishouDetachedPolicy.java'), join(project, 'tests/KuaishouDetachedPolicyTest.java'),
  join(project, 'src/dev/local/commentcollector/DouyinGuidance.java'), join(project, 'tests/DouyinGuidanceTest.java'),
  join(project, 'src/dev/local/commentcollector/Platforms.java'), join(project, 'src/dev/local/commentcollector/UserLinkTarget.java'), join(project, 'tests/UserLinkTargetTest.java'),
  join(project, 'src/dev/local/commentcollector/CurrentTargetPolicy.java'), join(project, 'tests/CurrentTargetPolicyTest.java'),
  join(project, 'src/dev/local/commentcollector/VisibleTitleMatch.java'), join(project, 'tests/VisibleTitleMatchTest.java'),
  join(project, 'src/dev/local/commentcollector/SnapshotMapper.java'), join(project, 'src/dev/local/commentcollector/PublicMapper.java'),
  join(project, 'src/dev/local/commentcollector/Analysis.java'),
  join(project, 'src/dev/local/commentcollector/WeakIdentityMap.java'),
  join(project, 'src/dev/local/commentcollector/CommentPageCache.java'), join(project, 'src/dev/local/commentcollector/RegionFilter.java'), join(project, 'src/dev/local/commentcollector/InitialCommentFilter.java'),
  join(project, 'src/dev/local/commentcollector/NativePageGate.java'), join(project, 'src/dev/local/commentcollector/NativeEofGuard.java'), join(project, 'src/dev/local/commentcollector/TargetPostGate.java'), join(project, 'src/dev/local/commentcollector/CollectorBackoff.java'),
  join(project, 'src/dev/local/commentcollector/XhsMapper.java'), join(project, 'src/dev/local/commentcollector/XhsCommentMapper.java'), join(project, 'src/dev/local/commentcollector/XhsProfileMapper.java'), join(project, 'src/dev/local/commentcollector/ModelReader.java'),
  join(project, 'tests/com/kuaishou/android/model/mix/QSubComment.java'), join(project, 'tests/j6a/a.java'),
  join(project, 'tests/MapperTest.java'), join(project, 'tests/RecoveryTest.java'), join(project, 'tests/NativePagerTest.java'), join(project, 'tests/XhsMapperTest.java'), join(project, 'tests/XhsCommentMapperTest.java'), join(project, 'tests/XhsProfileMapperTest.java'), join(project, 'tests/DouyinUserMapperTest.java'), join(project, 'tests/InitialCommentFilterTest.java'), join(project, 'tests/CollectorBackoffTest.java'), join(project, 'src/dev/local/commentcollector/MessageTemplateStore.java'), join(project, 'tests/MessageTemplateStoreTest.java')], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.MapperTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.DouyinRootPolicyTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.TaskProgressTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.KuaishouDetachedPolicyTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.DouyinGuidanceTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.UserLinkTargetTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.CurrentTargetPolicyTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.VisibleTitleMatchTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.RecoveryTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.NativePagerTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.XhsMapperTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.XhsCommentMapperTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.XhsProfileMapperTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.DouyinUserMapperTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.InitialCommentFilterTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.CollectorBackoffTest'], { stdio: 'inherit' });
execFileSync(join(java, 'bin/java'), ['-ea', '-cp', `${json}:${join(runtime, 'test-classes')}`, 'dev.local.commentcollector.MessageTemplateStoreTest'], { stdio: 'inherit' });
