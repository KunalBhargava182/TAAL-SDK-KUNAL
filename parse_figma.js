const fs = require('fs');
const d = fs.readFileSync('C:/Users/vetar/.claude/projects/C--Users-vetar-OneDrive-Desktop-taal-sdk-project/8f2c8ff3-f8bd-436a-8fd7-241a9ef8f292/tool-results/mcp-Figma-get_metadata-1771356010509.txt', 'utf8');
const j = JSON.parse(d);
const t = j[0].text;
const re = /frame id="([^"]+)" name="([^"]+)"[^>]*?width="(\d+)"[^>]*?height="(\d+)"/g;
let m;
while ((m = re.exec(t)) !== null) {
  const w = parseInt(m[3]), h = parseInt(m[4]);
  if (w > 350 && h > 700) {
    console.log(m[1] + ' | ' + m[2] + ' | ' + w + 'x' + h);
  }
}
