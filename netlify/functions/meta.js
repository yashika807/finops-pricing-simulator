const { meta } = require('../../lib/finops');
exports.handler = async () => ({
  statusCode: 200,
  headers: { 'Content-Type': 'application/json', 'Cache-Control': 'public, s-maxage=86400' },
  body: JSON.stringify(meta()),
});
