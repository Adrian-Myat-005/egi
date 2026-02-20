import { Sequelize, DataTypes, Model } from 'sequelize';
import path from 'path';

const sequelize = new Sequelize({
  dialect: 'sqlite',
  storage: path.join(__dirname, '../database.sqlite'),
  logging: false,
});

export class User extends Model {
  declare id: number;
  declare username: string;
  declare password: string;
  declare isPremium: boolean;
  declare subscriptionExpiry: Date | null;
  declare assignedKey: string | null;
}

User.init({
  id: { type: DataTypes.INTEGER, autoIncrement: true, primaryKey: true },
  username: { type: DataTypes.STRING, unique: true, allowNull: false },
  password: { type: DataTypes.STRING, allowNull: false },
  isPremium: { type: DataTypes.BOOLEAN, defaultValue: false },
  subscriptionExpiry: { type: DataTypes.DATE, allowNull: true },
  assignedKey: { type: DataTypes.TEXT, allowNull: true },
}, { sequelize, modelName: 'user' });

export class Node extends Model {
    declare id: number;
    declare regionName: string;
    declare ssKey: string;
}

Node.init({
    id: { type: DataTypes.INTEGER, autoIncrement: true, primaryKey: true },
    regionName: { type: DataTypes.STRING, allowNull: false },
    ssKey: { type: DataTypes.TEXT, allowNull: false },
}, { sequelize, modelName: 'node' });

export { sequelize };
