const { meta } = require('../lib/finops');
module.exports = (req, res) => {
  res.setHeader('Cache-Control', 's-maxage=86400');
  res.status(200).json(meta());
};
