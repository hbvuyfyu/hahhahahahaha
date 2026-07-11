FROM node:18-slim

WORKDIR /app

COPY server/package.json ./
RUN npm install --production

COPY server/ ./

EXPOSE 3000

CMD ["node", "index.js"]
