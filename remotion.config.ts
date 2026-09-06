// Remotion CLI config for the Meera demo composition (src/remotion).
// Studio:  npx remotion studio --no-open
// Render:  npx remotion render MeeraDemo out/meera-demo.mp4
import { Config } from '@remotion/cli/config';

Config.setEntryPoint('src/remotion/index.ts');
Config.setVideoImageFormat('jpeg');
Config.setOverwriteOutput(true);
