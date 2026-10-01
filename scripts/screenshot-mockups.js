const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');
const http = require('http');
const { createReadStream, existsSync, statSync } = require('fs');

const MOCK_DIR = path.resolve(__dirname, '..', 'docs', 'mock');
const OUT_DIR = path.resolve(process.env.MOCKUP_OUT || path.join(require('os').homedir(), '.cache', 'lightforge', 'mockup-screenshots'));

// Viewports matching Android device classes
const VIEWPORTS = [
  { name: 'compact-phone', width: 412, height: 900, label: 'Compact (Phone, ~412dp)' },
  { name: 'medium-foldable', width: 673, height: 900, label: 'Medium (Foldable half-open, ~673dp)' },
  { name: 'expanded-tablet', width: 840, height: 900, label: 'Expanded (Tablet, ~840dp)' },
];

// All base screens (excluding versioned variants)
const SCREENS = [
  '01-fotos.html',
  '02-colecciones.html',
  '03-album.html',
  '04-buscar-inicio.html',
  '05-buscar-resultados.html',
  '06-seleccion.html',
  '07-visor.html',
  '08-detalles.html',
  '09-edicion.html',
  '10-momento.html',
  '11-permisos.html',
  '12-estados.html',
  '13-ajustes.html',
  '14-yo.html',
  '15-papelera.html',
  '16-edicion-video.html',
];

// MIME types
const MIME = {
  '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml',
  '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
};

function startServer() {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      let urlPath = decodeURIComponent(req.url.split('?')[0]);
      if (urlPath === '/') urlPath = '/index.html';
      let filePath = path.join(MOCK_DIR, urlPath);
      if (!existsSync(filePath) && urlPath.startsWith('/frames/')) {
        // Try serving from mock dir
        filePath = path.join(MOCK_DIR, urlPath);
      }
      if (!existsSync(filePath)) {
        res.writeHead(404); res.end('Not found: ' + urlPath); return;
      }
      if (statSync(filePath).isDirectory()) {
        filePath = path.join(filePath, 'index.html');
        if (!existsSync(filePath)) { res.writeHead(404); res.end('Dir listing disabled'); return; }
      }
      const ext = path.extname(filePath);
      res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
      createReadStream(filePath).pipe(res);
    });
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      console.log('Server on port', port);
      resolve({ server, port });
    });
  });
}

async function main() {
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const { server, port } = await startServer();
  const browser = await chromium.launch({ headless: true });
  const results = [];

  for (const vp of VIEWPORTS) {
    const vpDir = path.join(OUT_DIR, vp.name);
    fs.mkdirSync(vpDir, { recursive: true });

    for (const screen of SCREENS) {
      const context = await browser.newContext({
        viewport: { width: vp.width, height: vp.height },
        deviceScaleFactor: 2, // High-DPI for quality
      });
      const page = await context.newPage();
      const url = 'http://127.0.0.1:' + port + '/screens/' + screen;

      try {
        await page.goto(url, { waitUntil: 'networkidle', timeout: 15000 });
        await page.waitForTimeout(500); // Extra settle time

        const name = screen.replace('.html', '');
        const screenshotPath = path.join(vpDir, name + '.png');
        await page.screenshot({ path: screenshotPath, fullPage: true });

        // Collect console errors
        const consoleErrors = [];
        page.on('console', msg => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });

        // Detect overflow/clipping issues
        const issues = await page.evaluate(() => {
          const problems = [];
          const doc = document.documentElement;
          // Horizontal overflow
          if (doc.scrollWidth > doc.clientWidth) {
            problems.push({ type: 'horizontal-overflow', detail: 'scrollWidth=' + doc.scrollWidth + ' > clientWidth=' + doc.clientWidth });
          }
          // Elements wider than viewport
          const vw = window.innerWidth;
          document.querySelectorAll('*').forEach(el => {
            const rect = el.getBoundingClientRect();
            if (rect.width > vw + 1 && !el.closest('.photo-grid') && el.tagName !== 'HTML' && el.tagName !== 'BODY') {
              const tag = el.tagName.toLowerCase();
              const cls = el.className && typeof el.className === 'string' ? el.className.split(' ').slice(0, 3).join('.') : '';
              const id = el.id ? '#' + el.id : '';
              problems.push({ type: 'element-overflow', tag, cls, id, width: Math.round(rect.width), viewport: vw });
            }
          });
          // Icon placeholders (data-icon with no rendered content)
          document.querySelectorAll('[data-icon]').forEach(el => {
            const styles = getComputedStyle(el);
            const hasContent = styles.content !== 'none' || el.textContent.trim() !== '' ||
              el.querySelector('img, svg, canvas') !== null;
            const rect = el.getBoundingClientRect();
            if (rect.width > 0 && rect.height > 0) {
              const bgImage = styles.backgroundImage;
              const maskImage = styles.webkitMaskImage || styles.maskImage;
              if (!hasContent && bgImage === 'none' && maskImage === 'none') {
                problems.push({ type: 'empty-icon-placeholder', selector: (el.tagName.toLowerCase()) + (el.className ? '.' + el.className.split(' ').join('.') : ''), iconName: el.dataset.icon });
              }
            }
          });
          // Clipped text (text-overflow issues)
          document.querySelectorAll('h1, h2, h3, p, span, button, strong, small').forEach(el => {
            if (el.scrollWidth > el.clientWidth && el.clientWidth > 0) {
              const styles = getComputedStyle(el);
              if (styles.overflow === 'hidden' || styles.textOverflow === 'ellipsis') {
                const cls = el.className && typeof el.className === 'string' ? el.className.split(' ').slice(0, 2).join('.') : '';
                problems.push({ type: 'text-clipped', text: el.textContent.trim().slice(0, 50), cls });
              }
            }
          });
          return problems;
        });

        results.push({ screen: name, viewport: vp.name, issueCount: issues.length, issues: issues.slice(0, 20) });
        console.log('OK: ' + vp.name + '/' + name + ' — ' + issues.length + ' issues');
      } catch (err) {
        console.error('FAIL: ' + vp.name + '/' + screen + ' — ' + err.message);
        results.push({ screen, viewport: vp.name, issueCount: -1, error: err.message });
      }
      await context.close();
    }
  }

  // Now take versioned variants at compact only for comparison
  const variantDir = path.join(OUT_DIR, 'variants-compact');
  fs.mkdirSync(variantDir, { recursive: true });
  const variants = fs.readdirSync(path.join(MOCK_DIR, 'screens'))
    .filter(f => f.endsWith('.html') && /\-v\d+\.html$/.test(f));

  for (const variant of variants) {
    const context = await browser.newContext({
      viewport: { width: 412, height: 900 },
      deviceScaleFactor: 2,
    });
    const page = await context.newPage();
    try {
      await page.goto('http://127.0.0.1:' + port + '/screens/' + variant, { waitUntil: 'networkidle', timeout: 15000 });
      await page.waitForTimeout(500);
      const name = variant.replace('.html', '');
      await page.screenshot({ path: path.join(variantDir, name + '.png'), fullPage: true });
      console.log('OK variant: ' + name);
    } catch (err) {
      console.error('FAIL variant: ' + variant + ' — ' + err.message);
    }
    await context.close();
  }

  // Also screenshot the overview index.html at all three widths
  for (const vp of VIEWPORTS) {
    const context = await browser.newContext({
      viewport: { width: vp.width, height: vp.height },
      deviceScaleFactor: 2,
    });
    const page = await context.newPage();
    try {
      await page.goto('http://127.0.0.1:' + port + '/index.html', { waitUntil: 'networkidle', timeout: 30000 });
      await page.waitForTimeout(1000);
      await page.screenshot({ path: path.join(OUT_DIR, 'overview-' + vp.name + '.png'), fullPage: true });
      console.log('OK overview: ' + vp.name);
    } catch (err) {
      console.error('FAIL overview: ' + vp.name + ' — ' + err.message);
    }
    await context.close();
  }

  await browser.close();
  server.close();

  // Write analysis JSON
  const analysisPath = path.join(OUT_DIR, 'analysis.json');
  fs.writeFileSync(analysisPath, JSON.stringify(results, null, 2));
  console.log('\nWritten ' + analysisPath);
  console.log('Total screens: ' + results.length);
  console.log('Total issues: ' + results.reduce((a, r) => a + Math.max(0, r.issueCount), 0));
}

main().catch(err => { console.error(err); process.exit(1); });

