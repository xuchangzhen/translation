FROM node:22-alpine
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY src ./src
ENV NODE_ENV=production PORT=8787
USER node
EXPOSE 8787
CMD ["node", "src/index.mjs"]
