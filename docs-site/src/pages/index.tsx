import Link from "@docusaurus/Link";
import useDocusaurusContext from "@docusaurus/useDocusaurusContext";
import Layout from "@theme/Layout";
import type { ReactNode } from "react";

const areas: { title: string; text: string; to: string }[] = [
  { title: "Getting started", text: "From a running gateway to a live value.", to: "/manual/getting-started" },
  { title: "The gateway", text: "Health, settings, backups, logs, and diagnostics.", to: "/manual/platform" },
  { title: "Tags", text: "Named values, quality, and history.", to: "/manual/getting-started" },
  { title: "Screens", text: "Draw a page in Control Canvas and open it live.", to: "/manual/getting-started" },
];

export default function Home(): ReactNode {
  const { siteConfig } = useDocusaurusContext();
  return (
    <Layout title={siteConfig.title} description="How to run a plant with ERIC, from the screen.">
      <header className="oip-hero">
        <h1>ERIC</h1>
        <p>Engineered Runtime Interface &amp; Control. {siteConfig.tagline}. The manual shows the clicks. Scripts and the API stay available when you want them.</p>
        <p>
          <Link className="button button--primary button--lg" to="/manual/getting-started">
            Start here
          </Link>
        </p>
      </header>
      <section className="oip-grid">
        {areas.map((area) => (
          <Link key={area.title} className="oip-card" to={area.to}>
            <strong>{area.title}</strong>
            <span>{area.text}</span>
          </Link>
        ))}
      </section>
    </Layout>
  );
}
