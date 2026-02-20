import express from 'express';
import cors from 'cors';
import bcrypt from 'bcryptjs';
import { sequelize, User, VpnNode } from './models';
import { generateToken, authenticateToken } from './auth';
import { getVpnConfig } from './vpn';

const app = express();
app.use(cors());
app.use(express.json());

const PORT = process.env.PORT || 3000;

app.post('/api/auth/register', async (req, res) => {
  const { username, password } = req.body;
  try {
    const hashedPassword = await bcrypt.hash(password, 10);
    const user = await User.create({ username, password: hashedPassword });
    res.json({ token: generateToken(user), user: { username, isPremium: false } });
  } catch (error) {
    res.status(400).json({ error: 'USER_ALREADY_EXISTS' });
  }
});

app.post('/api/auth/login', async (req, res) => {
  const { username, password } = req.body;
  const user = await User.findOne({ where: { username } });
  if (user && await bcrypt.compare(password, user.password)) {
    res.json({ 
      token: generateToken(user), 
      user: { 
        username, 
        isPremium: user.isPremium,
        expiry: user.subscriptionExpiry 
      } 
    });
  } else {
    res.status(401).json({ error: 'INVALID_CREDENTIALS' });
  }
});

app.get('/api/vpn/config', authenticateToken, getVpnConfig);

// Public test key for users who haven't bought a VPS yet
app.get('/api/vpn/test-key', (req, res) => {
  const testKey = "ss://YWVzLTEyOC1nY206RWdpU2VjcmV0UGFzc3dvcmQyMDI2@159.223.1.1:8388";
  res.json({ config: testKey, message: "SAMPLE_TEST_KEY_FOR_EGI_SHIELD" });
});

// Admin-only: Create a node (Mock for this demo)
app.post('/api/admin/nodes', async (req, res) => {
  const { name, ip, port, ssPassword, method } = req.body;
  const node = await VpnNode.create({ name, ip, port, ssPassword, method });
  res.json(node);
});

// Admin-only: Make user premium (Mock for this demo)
app.post('/api/admin/promote', async (req, res) => {
  const { username, months } = req.body;
  const user = await User.findOne({ where: { username } });
  if (user) {
    const expiry = new Date();
    expiry.setMonth(expiry.getMonth() + (months || 1));
    user.isPremium = true;
    user.subscriptionExpiry = expiry;
    await user.save();
    res.json({ success: true, expiry });
  } else {
    res.status(404).json({ error: 'USER_NOT_FOUND' });
  }
});

sequelize.sync().then(() => {
  app.listen(PORT, async () => {
    console.log(`EGI_CORE_API_RUNNING_ON_PORT_${PORT}`);
    
    // Seed an initial node for testing
    if ((await VpnNode.count()) === 0) {
      await VpnNode.create({
        name: "EGI_SG_PREMIUM_1",
        ip: "159.223.1.1", // Example IP
        port: 8388,
        ssPassword: "EgiSecretPassword2026",
        method: "aes-128-gcm"
      });
      console.log("SEEDING_INITIAL_VPN_NODE");
    }
  });
});
