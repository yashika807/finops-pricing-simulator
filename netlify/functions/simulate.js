const { report } = require('../../lib/finops');
exports.handler = async (event) => {
  try {
    const csv = event.httpMethod === 'POST'
      ? (event.isBase64Encoded ? Buffer.from(event.body || '', 'base64').toString('utf8') : (event.body || ''))
      : null;
    const data = await report(event.queryStringParameters || {}, csv);
    return {
      statusCode: 200,
      headers: { 'Content-Type': 'application/json',
        'Cache-Control': event.httpMethod === 'GET' ? 'public, s-maxage=900, stale-while-revalidate=3600' : 'no-store' },
      body: JSON.stringify(data),
    };
  } catch (e) {
    return { statusCode: 502, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' },
      body: JSON.stringify({ error: e.message || String(e) }) };
  }
};
