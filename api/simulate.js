const { report } = require('../lib/finops');
module.exports = async (req, res) => {
  try {
    const csv = req.method === 'POST' ? (typeof req.body === 'string' ? req.body : '') : null;
    const data = await report(req.query || {}, csv);
    // Live prices are cached at Vercel's edge for 15 minutes so repeated visits stay fast.
    res.setHeader('Cache-Control', req.method === 'GET' ? 's-maxage=900, stale-while-revalidate=3600' : 'no-store');
    res.status(200).json(data);
  } catch (e) {
    res.setHeader('Cache-Control', 'no-store');
    res.status(502).json({ error: e.message || String(e) });
  }
};
